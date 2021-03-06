package org.derekwyatt.ahalog

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.DateTime
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives._
import akka.http.scaladsl.server.RouteResult.{ Complete, Rejected }
import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ InHandler, GraphStage, GraphStageLogic, OutHandler }
import akka.stream.{ Attributes, Inlet, FlowShape, Outlet }
import akka.util.{ ByteString, Timeout }
import scala.concurrent.{ ExecutionContext, Future }

trait AhaLogDirectives extends BasicDirectives with MiscDirectives {
  /**
   * A directive that will log to the given logger in a format that resembles the apache access log.
   *
   * @param log The Logger instance to which we want to log INFO level messages.
   */
  def accessLog(log: LoggingAdapter)(implicit ec: ExecutionContext, to: Timeout, mat: Materializer): Directive0 =
    mapInnerRoute { originalRoute => ctx => {
      // Extract intersting inforamtion from the request
      def fromForwarded = ctx.request.header[`X-Forwarded-For`].flatMap(h => h.addresses.headOption)
      def fromRemoteAddress = ctx.request.header[`Remote-Address`].map(_.address)
      def fromRealIp = ctx.request.header[`X-Real-Ip`].map(_.address)

      def remoteAddress = fromForwarded orElse fromRemoteAddress orElse fromRealIp
      def remoteIp = remoteAddress.flatMap(ra => ra.toOption.map(_.getHostAddress)).getOrElse("-")

      def method = ctx.request.method.value
      def path = ctx.request.uri.toString
      def now = {
        val n = DateTime.now
        f"${n.day}%02d/${n.monthStr}/${n.year}:${n.hour}%02d:${n.minute}%02d:${n.second}%02d -0000"
      }
      def proto = ctx.request.protocol.value

      def username: String = ctx.request.header[Authorization].flatMap(auth => auth.credentials match {
        case BasicHttpCredentials(username, _) => Some(username)
        case _ => None
      }).getOrElse("-")

      def mkString(code: String, size: String): String = s"""$remoteIp - $username [$now] "$method $path $proto" $code $size"""

      // Execute the original function that we're wrapping
      originalRoute(ctx).map {
        // We have a valid HTTPResponse.  In order to finish the log, we need to get the size of the
        // body.  However, the body is an Akka stream and may not even be in memory - it could still
        // be on the wire - and so we need to inject some code into the stream in order to lazily
        // compute the size that was marshalled.
        case Complete(rsp) if rsp.status.allowsEntity =>
          val code = rsp.status.intValue.toString
          // Transform the response by modifying the response entity with a newly injected stream
          // element
          Complete(
            rsp.mapEntity { entity =>
              entity.transformDataBytes(
                // The SideEffector will allow us to accumulate the size of the byte strings passing
                // through until it eventually results in a completion that we can log
                SideEffector.flow[ByteString, Long](
                  size => log.info(mkString(code, size.toString)),
                  ex => log.error(mkString(code, ex.getMessage()))
                )(0L)((acc, bs) => acc + bs.size)
              )
            }
          )
        case rslt @ Complete(rsp) =>
          log.info(mkString(rsp.status.intValue.toString, "-"))
          rslt
        case rslt @ Rejected(rejections) =>
          val reason = rejections.map(_.getClass.getName).mkString(",")
          log.info(mkString(reason, "-"))
          rslt
      }
    }
  }
}

class SideEffector[A, B](onComplete: B => Unit, onFailure: Throwable => Unit, zero: B, acc: (B, A) => B) extends GraphStage[FlowShape[A, A]] {

  val in = Inlet[A]("SideEffector.in")
  val out = Outlet[A]("SideEffector.out")

  override val shape = FlowShape.of(in, out)

  override def createLogic(attr: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      var accData = zero
      var called = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val data = grab(in)
          accData = acc(accData, data)
          push(out, data)
        }

        override def onUpstreamFinish(): Unit = {
          called = true
          onComplete(accData)
          super.onUpstreamFinish()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          onFailure(ex)
          super.onUpstreamFailure(ex)
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)

        override def onDownstreamFinish(): Unit = {
          called = true
          onComplete(accData)
          super.onDownstreamFinish()
        }
      })
    }
}

object SideEffector {
  def flow[A, B](onComplete: B => Unit, onFailure: Throwable => Unit = _ => ())(zero: B)(acc: (B, A) => B): Flow[A, A, NotUsed] =
    Flow.fromGraph(new SideEffector(onComplete, onFailure, zero, acc))
}
