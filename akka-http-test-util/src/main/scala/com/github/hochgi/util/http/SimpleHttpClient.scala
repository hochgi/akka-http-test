package com.github.hochgi.util.http

import java.io.InputStream
import akka.actor.ActorSystem
import akka.http.scaladsl._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws._
import akka.stream.stage.{OutHandler, InHandler, GraphStageLogic, GraphStage}
import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}


trait SimpleResponseHandler[T] {
  def mkStringRepr(t: T): String
  def mkResponseOf(status: Int, headers: Seq[(String,String)], contentType: String, dataBytes: Source[ByteString,Any])(implicit ec: ExecutionContext): Future[SimpleResponse[T]]
}

object SimpleResponseHandler {
  // default SimpleResponseHandler should be found automatically, and hence, is defined here.
  // alternative predefined handlers should be put in `SimpleResponse` companion object.
  implicit object ByteArrayHandler extends SimpleResponseHandler[Array[Byte]] {

    def mkStringRepr(payload: Array[Byte]): String = payload match {
      case arr if arr.isEmpty => ""
      case arr => new String(arr, "UTF-8")
    }

    def mkResponseOf(status: Int,
                     headers: Seq[(String,String)],
                     contentType: String,
                     dataBytes: Source[ByteString,Any])(implicit ec: ExecutionContext): Future[SimpleResponse[Array[Byte]]] = {
      dataBytes.runFold(ByteString(""))(_ ++ _)(SimpleHttpClient.materializer).map(_.toArray).map { arr =>
        SimpleResponse(status, headers, contentType -> arr)
      }
    }
  }
}

object SimpleResponse {

  type ContentType = String
  type ResponseBody[T] = (ContentType, T)

  // if you want a SimpleResponse[T] for T != Array[Byte],
  // import a SimpleResponseHandler[T] from here (or implement your own)
  object Implicits {
    implicit object InputStreamHandler extends SimpleResponseHandler[InputStream] {
      def mkStringRepr(payload: InputStream) = payload.toString()
      def mkResponseOf(status: Int,
                     headers: Seq[(String,String)],
                     contentType: String,
                     dataBytes: Source[ByteString,Any])(implicit ec: ExecutionContext): Future[SimpleResponse[InputStream]] = {
        val is = dataBytes.runWith(StreamConverters.asInputStream(30.seconds))(SimpleHttpClient.materializer)
        Future.successful(SimpleResponse(status, headers, contentType -> is))
      }
    }
  }
}

import SimpleResponse._

case class SimpleResponse[T : SimpleResponseHandler](status: Int, headers: Seq[(String,String)], body: ResponseBody[T]) {
  def contentType = body._1
  def payload = body._2

  override def toString() = {
    val handler = implicitly[SimpleResponseHandler[T]]
    val body = handler.mkStringRepr(payload)
    s"""SimpleResponse($status, ${headers.mkString("[",", ","]")}, ($contentType, "$body"))"""
  }
}

object SimpleHttpClient extends LazyLogging {

  private[http] implicit val sys = {
    val config = ConfigFactory.load()
    val maxConnections = Try(config.getInt("hochgi.util.http.akka.http.host-connection-pool.max-open-requests")).getOrElse(128)
    //TODO: there's probably a much easier way to obtain this... should be implemented properly.
    val specialConfigurationForActorSystem = ConfigFactory.parseString(s"""
       |akka {
       |  actor {
       |    provider = "akka.actor.LocalActorRefProvider"
       |  }
       |  http {
       |    host-connection-pool {
       |      max-open-requests = $maxConnections
       |    }
       |  }
       |}
      """.stripMargin)
    ActorSystem("SimpleHttpClient",specialConfigurationForActorSystem)
  }
  private[http] implicit val mat = ActorMaterializer()
  private[http] val http = Http()

  //just in case we need a materializer in the tests...
  private[http] def materializer = mat

  private def mkHeaders(headers: Seq[(String,String)]) = headers.map {
    case (name, value) => HttpHeader.parse(name, value) match {
      case ParsingResult.Ok(header, _) => header
      case ParsingResult.Error(err) => throw new IllegalArgumentException(err.formatPretty)
    }
  }.toList

  private def mkURI(uri: String, queryParams: Seq[(String,String)]) = {
    if (queryParams.isEmpty) uri
    else {
      uri + queryParams.map {
        case (key, value) => {
          val k = java.net.URLEncoder.encode(key, "UTF-8")
          val v = java.net.URLEncoder.encode(value, "UTF-8")
          s"$k=$v"
        }
      }.mkString("?", "&", "")
    }
  }

  private def resToSimpleRes[T](res: HttpResponse, handler: SimpleResponseHandler[T])(implicit ec: ExecutionContext) = res match {
    case HttpResponse(s,h,e,_) => {
      val headers = h.map{ header => header.name -> header.value}
      val contentType = e.contentType.toString
      val status = s.intValue()
//      val dataBytesTry = Try(e.withSizeLimit(-1).dataBytes).recover {
//        case ex: IllegalArgumentException => {
//          logger.error("could not receive response entity without size limit",ex)
//          e.dataBytes
//        }
//      }
//      dataBytesTry match {
//        case Success(dataBytes) => handler.mkResponseOf (status, headers, contentType, dataBytes)
//        case Failure(exception) => Future.failed[SimpleResponse[T]](exception)
//      }
       handler.mkResponseOf(status, headers, contentType, e.withSizeLimit(-1).dataBytes)
    }
  }

  private def request[T : SimpleResponseHandler](_method: HttpMethod,
                      _uri: String,
                      queryParams: Seq[(String,String)],
                      headers: Seq[(String,String)],
                      _entity: RequestEntity)
                     (implicit ec: ExecutionContext): Future[SimpleResponse[T]] = {

    val _headers = mkHeaders(headers)
    val uriWithqp = mkURI(_uri,queryParams)

    val req = HttpRequest(
      method = _method,
      uri = uriWithqp,
      headers = _headers,
      entity = _entity)

    val con = http.superPool[None.type]()
    val f = Source.single(req -> None).via(con).runWith(Sink.head)
    f.flatMap{
      case (Success(res),_) => resToSimpleRes(res,implicitly[SimpleResponseHandler[T]])
      case (Failure(err),_) => Future.failed[SimpleResponse[T]](err)
    }
  }

  private def cType(ct: Option[String]) = ct match {
    case None => ContentTypes.NoContentType
    case Some(x) => ContentType.parse(x) match {
      case Right(r) => r
      case Left(errors) => {
        val msg = errors.map(_.formatPretty).mkString("\n")
        throw new IllegalArgumentException("Malformed Content-Type: \n" + msg)
      }
    }
  }

  private def cTypeNonBin(ct: Option[String]): ContentType.NonBinary = cType(ct) match {
    case x: ContentType.NonBinary => x
    case x => throw new IllegalArgumentException(s"expected a non-binary Content-Type. actual: `$x`")
  }

  sealed trait Body {
    def entity(contentType: Option[String]): RequestEntity

    def contentType(ct: String): akka.http.scaladsl.model.ContentType = ContentType.parse(ct) match {
      case Right(r) => r
      case Left(errors) => {
        val msg = errors.map(_.formatPretty).mkString("\n")
        throw new IllegalArgumentException("Malformed Content-Type: \n" + msg)
      }
    }
  }

  object Body {
    import scala.language.implicitConversions

    implicit def apply(body: String): Body = new BodyFromString(body)
    implicit def apply(body: Array[Byte]): Body = new BodyFromBytes(body)
    implicit def apply(body: ByteString): Body = new BodyFromByteString(body)

    private class BodyFromString(body: String) extends Body {
      override def entity(ct: Option[String]) = ct match {
        case None => HttpEntity(body)
        case Some(c) => HttpEntity(contentType(c),body)
      }
      override def contentType(ct: String): ContentType.NonBinary = super.contentType(ct) match {
        case x: ContentType.NonBinary => x
        case x => throw new IllegalArgumentException(s"expected a non-binary Content-Type. actual: `$x`")
      }
    }

    private class BodyFromBytes(body: Array[Byte]) extends Body {
      override def entity(ct: Option[String]) = ct match {
        case None => HttpEntity(body)
        case Some(c) => HttpEntity(contentType(c), body)
      }
    }

    private class BodyFromByteString(body: ByteString) extends Body {
      override def entity(ct: Option[String]) = ct match {
        case None => HttpEntity(body)
        case Some(c) => HttpEntity(contentType(c), body)
      }
    }
  }

  import annotation.implicitNotFound
  @implicitNotFound("implicit only works for `String`,`Array[Byte]`, or `ByteString`")
  sealed trait SimpleMessageHandler[T] {
    def toMessage(t: T): Message
    def fromMessage(m: Message)(implicit ec: ExecutionContext): Future[T]
  }

  object SimpleMessageHandler {
    import scala.language.implicitConversions

    implicit object StringMessageHandler extends SimpleMessageHandler[String]{
      override def toMessage(msg: String) = TextMessage(msg)
      override def fromMessage(m: Message)(implicit ec: ExecutionContext) = m match {
        case tm: TextMessage => tm.textStream.runFold("")(_+_)
        case bm: BinaryMessage => bm.dataStream.runFold(ByteString(""))(_++_).map(_.utf8String)
      }
    }

    implicit object BytesMessageHandler extends SimpleMessageHandler[Array[Byte]]{
      override def toMessage(msg: Array[Byte]) = BinaryMessage(ByteString(msg))
      override def fromMessage(m: Message)(implicit ec: ExecutionContext) = m match {
        case tm: TextMessage => tm.textStream.runFold("")(_+_).map(_.getBytes("UTF-8"))
        case bm: BinaryMessage => bm.dataStream.runFold(ByteString(""))(_++_).map(_.toArray)
      }
    }

    implicit object ByteStringMessageHandler extends SimpleMessageHandler[ByteString]{
      override def toMessage(msg: ByteString) = BinaryMessage(msg)
      override def fromMessage(m: Message)(implicit ec: ExecutionContext) = m match {
        case tm: TextMessage => tm.textStream.runFold("")(_+_).map(ByteString.apply)
        case bm: BinaryMessage => bm.dataStream.runFold(ByteString(""))(_++_)
      }
    }
  }

  private def graphStage[T](toMsg: T => Option[Message]) = new GraphStage[FlowShape[T, Message]] {

    val in = Inlet[T]("WebSocketMessageHandler.in")
    val out = Outlet[Message]("WebSocketMessageHandler.out")

    override val shape: FlowShape[T, Message] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

      var pending: Message = null

      override def preStart() = pull(in)

      setHandler(in, new InHandler {
        override def onPush(): Unit = toMsg(grab(in)) match {
          case None => completeStage()
          case Some(msg) => {
            if (isAvailable(out)) {
              push(out, msg)
              pull(in)
            }
            else pending = msg
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if(pending ne null) {
            push(out,pending)
            pending = null
            pull(in)
          }
        }
      })
    }
  }

  def ws[T : SimpleMessageHandler](uri: String,
                                   initiationMessage: T,
                                   subprotocol: Option[String] = None,
                                   queryParams: Seq[(String,String)] = Nil,
                                   headers: Seq[(String,String)] = Nil)(react: T => Option[T])
                                  (implicit ec: ExecutionContext) = {

    val simpleMessageHandler = implicitly[SimpleMessageHandler[T]]
    val h = mkHeaders(headers)
    val u = mkURI(uri,queryParams)
    val flow = Http().webSocketClientFlow(WebSocketRequest(u,h,subprotocol))
                     .mapAsync(1)(simpleMessageHandler.fromMessage)
                     .via(graphStage(react andThen {_.map(simpleMessageHandler.toMessage)}))

    val g = RunnableGraph.fromGraph[Future[WebSocketUpgradeResponse]](GraphDSL.create(flow) { implicit b =>

      f =>
      import GraphDSL.Implicits._

        val s = b.add(Source.single(simpleMessageHandler.toMessage(initiationMessage)))
        val c = b.add(Concat[Message](2))

        s ~> c.in(0)
             c.out ~> f ~> c.in(1)

        ClosedShape
    })

    g.run().flatMap{
      case ValidUpgrade(res, chosenSubprotocol) => {
        chosenSubprotocol.foreach(p => logger.debug(s"ws: chosenSubprotocol = $p"))
        resToSimpleRes(res, SimpleResponseHandler.ByteArrayHandler)
      }
      case InvalidUpgradeResponse(res, cause) => {
        logger.warn(s"ws: InvalidUpgradeResponse, cause = $cause")
        resToSimpleRes(res, SimpleResponseHandler.ByteArrayHandler)
      }
    }
  }

  def get[T : SimpleResponseHandler](uri: String,
          queryParams: Seq[(String,String)] = Nil,
          headers: Seq[(String,String)] = Nil)
         (implicit ec: ExecutionContext) =
    request[T](HttpMethods.GET,uri,queryParams,headers,HttpEntity.Empty)

  def put[T : SimpleResponseHandler](uri: String,
          body: Body,
          contentType: Option[String] = None,
          queryParams: Seq[(String,String)] = Nil,
          headers: Seq[(String,String)] = Nil)
         (implicit ec: ExecutionContext) =
    request[T](HttpMethods.PUT,uri,queryParams,headers,body.entity(contentType))

  def post[T : SimpleResponseHandler](uri: String,
           body: Body,
           contentType: Option[String] = None,
           queryParams: Seq[(String,String)] = Nil,
           headers: Seq[(String,String)] = Nil)
          (implicit ec: ExecutionContext) =
    request[T](HttpMethods.POST,uri,queryParams,headers,body.entity(contentType))

  def delete[T : SimpleResponseHandler](uri: String,
             queryParams: Seq[(String,String)] = Nil,
             headers: Seq[(String,String)] = Nil)
            (implicit ec: ExecutionContext) =
    request[T](HttpMethods.DELETE, uri,queryParams,headers,HttpEntity.Empty)

}


//TODO: following won't compile (seems like a scalac bug???) need to investigate this.
//import com.ning.http.client.AsyncHttpClient
//import scala.collection.JavaConverters._

//object SimpleHttpClient {
//
//  private[this] val client: AsyncHttpClient = new AsyncHttpClient
//  private type RequestBuilder = AsyncHttpClient#BoundRequestBuilder
//
//  private sealed trait Method
//  private case object GET extends Method
//
//  private case class POST(body: Body) extends Method
//  private sealed trait Body {
//    def contentType: Option[String]
//    def setBody(rb: RequestBuilder): RequestBuilder
//    def setBodyAndContentType(rb: RequestBuilder) = {
//      var result = setBody(rb)
//      contentType.foreach { ct =>
//        result = result.addHeader("Content-Type",ct)
//      }
//      result
//    }
//  }
//
//  private case class StringBody(body: String, contentType: Option[String]) extends Body {
//    override def setBody(rb: RequestBuilder) = rb.setBody(body)
//  }
//
//  private case class BytesBody(body: Array[Byte], contentType: Option[String]) extends Body {
//    override def setBody(rb: RequestBuilder) = rb.setBody(body)
//  }
//
//  private[this] def request(method: Method, url: String, queryParams: Seq[(String,String)], headers: Seq[(String,String)]) = {
//
//    val requestBuilder = {
//
//      val rb = method match {
//        case GET => client.prepareGet(url).setFollowRedirects(true)
//        case POST(body) => body.setBodyAndContentType(client.preparePost(url))
//      }
//
//      headers.foldLeft(
//        queryParams.foldLeft(rb) {
//          case (reqBuilder, (k, v)) =>
//            reqBuilder.addQueryParameter(k, v)
//        }) {
//        case (reqBuilder, (k, v)) =>
//          reqBuilder.addHeader(k, v)
//      }
//    }
//
//    val res = requestBuilder.execute().get()
//
//    val resStatus = res.getStatusCode
//    val resHeaders = {
//      val keys: Seq[String] = res.getHeaders.keySet().asScala.toSeq
//      keys.map { key => key -> res.getHeader(key) }
//    }
//    val resBody = res.getContentType -> res.getResponseBodyAsBytes
//
//    SimpleResponse(resStatus,resHeaders,resBody)
//  }
//
//  def get(url: String,
//          queryParams: Seq[(String,String)] = Nil,
//          headers: Seq[(String,String)] = Nil) =
//    request(GET, url, queryParams, headers)
//
//  def post(url: String,
//           body: Array[Byte],
//           contentType: Option[String] = None,
//           queryParams: Seq[(String,String)] = Nil,
//           headers: Seq[(String,String)] = Nil) =
//    request(POST(BytesBody(body,contentType)), url, queryParams, headers)
//
//  def post(url: String,
//           body: String,
//           contentType: Option[String] = None,
//           queryParams: Seq[(String,String)] = Nil,
//           headers: Seq[(String,String)] = Nil) =
//    request(POST(StringBody(body,contentType)), url, queryParams, headers)
//}

