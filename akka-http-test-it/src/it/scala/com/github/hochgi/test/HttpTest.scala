package com.github.hochgi.test

import org.scalatest._
import com.github.hochgi.util.http.{SimpleHttpClient => Http}
import scala.concurrent._ , duration._ ,ExecutionContext.Implicits.global

class HttpTest extends FunSpec with Matchers {
  describe("akka-http-test"){
    it("should get an empty response with http wrapper"){
      val f = Http.get("http://localhost:9000/empty")
      Await.result(f,10.seconds).status should be(200)
    }
  }
}
