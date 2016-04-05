package controllers

import scala.concurrent.Future
import play.api.mvc._

object Application extends Controller {
  def handleGET(path:String) = Action.async{ implicit request => 
    Future.successful(Ok("").withHeaders("X-HOCHGI-HEADER" -> "hochgi"))
  }
}
