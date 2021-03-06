package net.hearthstats.hstatsapi

import java.io.IOException
import java.net.{ HttpURLConnection, URL }

import grizzled.slf4j.Logging
import net.hearthstats.config.UserConfig
import net.hearthstats.core.{ ArenaRun, HearthstoneMatch }
import org.json.simple.parser.JSONParser
import org.json.simple.{ JSONArray, JSONObject }

import scala.collection.JavaConversions.{ asScalaBuffer, mapAsJavaMap }

//TODO : replace this JSON implementation with a more typesafe one
class API(config: UserConfig) extends Logging {

  var lastMatchId = -1
  var message = ""

  lazy val awsKeys: Seq[String] = _get("users/premium") match {
    case None =>
      info("You are not a premium user")
      Nil
    case Some(resultObj) =>
      val json = resultObj.asInstanceOf[JSONObject]
      for (i <- Seq("aws_access_key", "aws_secret_key"))
        yield json.get(i).asInstanceOf[String]
  }

  lazy val premiumUserId: Option[String] = _get("users/premium") match {
    case None =>
      info("You are not a premium user")
      None
    case Some(resultObj) =>
      val json = resultObj.asInstanceOf[JSONObject]
      Some(json.get("user").asInstanceOf[JSONObject].get("id").toString)
  }

  def endCurrentArenaRun(): Option[ArenaRun] = {
    _get("arena_runs/end") match {
      case None =>
        warn("Error occurred while ending the arena run")
        None
      case Some(resultObj) =>
        val arenaRun = new ArenaRun(resultObj.asInstanceOf[JSONObject])
        info("Ended " + arenaRun.getUserClass + " arena run")
        Some(arenaRun)
    }
  }

  def createArenaRun(arenaRun: ArenaRun): Option[ArenaRun] =
    _post("arena_runs/new", arenaRun.toJsonObject) match {
      case Some(result) =>
        val resultingArenaRun = new ArenaRun(result)
        info(resultingArenaRun.getUserClass + " run created")
        Some(resultingArenaRun)
      case None =>
        warn("Error occurred while creating new arena run")
        None
    }

  def setDeckSlots(slots: Iterable[Option[Int]]): Unit = {
    val data = for ((d, i) <- slots.zipWithIndex)
      yield "slot_" + (i + 1) -> d.getOrElse(null)
    val jsonData = new JSONObject(data.toMap[Any, Any])
    _post("decks/slots", jsonData)
  }

  def createDeck(jsonDeck: JSONObject): Boolean = {
    _post("decks/create", jsonDeck) match {
      case Some(result) =>
        info("Deck was exported to HearthStats.net successfully")
        true
      case None =>
        warn("Error occurred while exporting deck to HearthStats.net")
        false
    }
  }

  /**
   * Returns the matchId if created OK.
   */
  def createMatch(hsMatch: HearthstoneMatch): Option[Int] = {
    _post("matches/new", hsMatch.toJsonObject) map { result =>
      lastMatchId = result.get("id").asInstanceOf[java.lang.Long].toInt
      lastMatchId
    }
  }

  def getLastArenaRun: ArenaRun =
    _get("arena_runs/show") match {
      case None => null
      case Some(resultObj) =>
        val arenaRun = new ArenaRun(resultObj.asInstanceOf[JSONObject])
        info("Fetched current " + arenaRun.getUserClass + " arena run")
        arenaRun
    }

  //can return JSONObject or JSONArray
  private def _get(method: String): Option[AnyRef] = {
    val baseUrl = config.apiBaseUrl + method + "?userkey="
    debug(s"API get $baseUrl********")
    try {
      val timeout = config.apiTimeoutMs
      val url = new URL(baseUrl + config.userKey.get)
      val conn = url.openConnection()
      conn.setConnectTimeout(timeout)
      conn.setReadTimeout(timeout)
      val inputStream = conn.getInputStream()
      val resultString = io.Source.fromInputStream(inputStream)("UTF-8").getLines.mkString("\n")
      info(s"API get result = $resultString")
      _parseResult(resultString)
    } catch {
      case e: IOException =>
        error("Error communicating with HearthStats.net (GET " + method + ")", e)
        None
    }

  }

  //can return JSONObject or JSONArray
  private def _parseResult(resultString: String): Option[AnyRef] = {

    val parser = new JSONParser
    try {
      val result = parser.parse(resultString).asInstanceOf[JSONObject]

      def flatResult =
        try Some(result.asInstanceOf[JSONObject]) catch {
          case e2: Exception =>
            error(s"could not parse $resultString", e2)
            None
        }

      if (result.get("status").toString.matches("success")) {
        try {
          message = result.get("message").toString
        } catch {
          case e: Exception => message = null
        }
        val data = result.get("data")
        if (data != null)
          try Some(data.asInstanceOf[JSONObject]) catch {
            case e: Exception =>
              try Some(data.asInstanceOf[JSONArray]) catch {
                case e1: Exception => flatResult
              }
          }
        else flatResult
      } else {
        error(result.get("message").asInstanceOf[String])
        None
      }
    } catch {
      case e: Exception =>
        error("Error parsing reply", e)
        None
    }

  }

  private def _post(method: String, jsonData: JSONObject): Option[JSONObject] = {
    val baseUrl = config.apiBaseUrl + method + "?userkey="
    debug(s"API post $baseUrl********")
    info("API post data = " + jsonData.toJSONString)
    val url = new URL(baseUrl + config.userKey.get)
    try {
      val httpcon = (url.openConnection()).asInstanceOf[HttpURLConnection]
      httpcon.setDoOutput(true)
      httpcon.setRequestProperty("Content-Type", "application/json")
      httpcon.setRequestProperty("Accept", "application/json")
      httpcon.setRequestMethod("POST")
      httpcon.setReadTimeout(config.apiTimeoutMs)
      httpcon.connect()
      val outputBytes = jsonData.toJSONString.getBytes("UTF-8")
      val os = httpcon.getOutputStream
      os.write(outputBytes)
      os.close()
      val resultString = io.Source.fromInputStream(httpcon.getInputStream).getLines.mkString("\n")
      info("API post result = " + resultString)
      _parseResult(resultString).map(_.asInstanceOf[JSONObject])
    } catch {
      case e: Exception =>
        error(s"Error communicating with HearthStats.net (POST $method)", e)
        None
    }
  }

  def getCards: List[JSONObject] =
    transformResult(_get("cards"), "Fetched all cards data from HearthStats.net")

  def getDecks: List[JSONObject] =
    transformResult(_get("decks/show"), "Fetched your decks from HearthStats.net")

  private def transformResult(result: Option[_], message: String) = result match {
    case Some(res) =>
      info(message)
      res.asInstanceOf[java.util.List[_]].map(_.asInstanceOf[JSONObject]).toList
    case None => List.empty
  }

}
