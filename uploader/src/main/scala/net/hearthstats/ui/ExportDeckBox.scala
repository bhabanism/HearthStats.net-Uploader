package net.hearthstats.ui

import java.awt.{Dimension, Font}
import java.util.{Observable, Observer}
import javax.imageio.ImageIO
import javax.swing._

import grizzled.slf4j.Logging
import net.hearthstats._
import net.hearthstats.analysis.{AnalyserEvent, DeckAnalyser, HearthstoneAnalyser}
import net.hearthstats.log.Log
import net.hearthstats.state.Screen
import net.hearthstats.ui.util.MigPanel
import net.hearthstats.util.HsRobot
import net.hearthstats.util.Translations.t
import org.json.simple.JSONObject

import scala.collection.JavaConversions._
import scala.swing._
import scala.swing.event.ButtonClicked

class ExportDeckBox(val monitor: Monitor) extends Frame with Observer with Logging {

  var hasDeck = false

  minimumSize = ExportDeckBox.minSizeWithoutDeck
  preferredSize = ExportDeckBox.minSizeWithoutDeck
  maximumSize = ExportDeckBox.maxSize

  title = t("export.heading") + " - HearthStats Companion"

  // Array of hero classes
  val localizedClassOptions = Array.ofDim[String](Constants.hsClassOptions.length)
  localizedClassOptions(0) = ""
  for (i <- 1 until localizedClassOptions.length) localizedClassOptions(i) = t(Constants.hsClassOptions(i))


  val panel = new MigPanel(
    layoutConstraints = "hidemode 3",
    colConstraints = "12[]12[]8[grow,fill]12",
    rowConstraints = "12[]8[]8[]12[]8[grow]12[]12"
  ) {

    // Heading
    contents += new Label {
      icon = new ImageIcon(ImageIO.read(getClass.getResource("/images/icon_32px.png")))
    }
    contents += (new Label {
      text = t("export.heading")
      font = font.deriveFont(16f)
    }, "span 2, wrap")

    // Status
    val status = new Label {
      text = t("export.status.not_ready")
      font = font.deriveFont(Font.BOLD)
      override def text_=(s: String): Unit = super.text_=(t("export.label.status") + " " + s)
    }
    contents += (status, "skip, span 2, wrap")

    // Instructions
    val instructions = new Label {
      text = ""
      // Using HTML as a simple way to make the label wrap
      override def text_=(s: String): Unit = super.text_=("<html><body style='width:100%'>" + s + "</body></html>")
    }
    contents += (instructions, "skip, span 2, wrap")

    // Deck Name
    val nameLabel = new Label {
      text = t("export.label.deckname")
      visible = false
    }
    contents += (nameLabel, "span 2, top, right, hmin 26, pad 2 0 0 0")
    val nameField = new TextField {
      visible = false
    }
    contents += (nameField, "top, wrap")

    // Class
    val classLabel = new Label {
      text = t("export.label.class")
      visible = false
    }
    contents += (classLabel, "span 2, top, right, hmin 26, pad 2 0 0 0")
    val classComboBox = new ComboBox(localizedClassOptions) {
      visible = false
    }
    contents += (classComboBox, "top, wrap")

    // Cards
    val cardLabel = new Label {
      text = t("export.label.cards")
      visible = false
    }
    contents += (cardLabel, "span 2, top, right, hmin 26, pad 2 0 0 0")
    val cardTextArea = new TextArea
    val cardScrollPane = new ScrollPane {
      contents = cardTextArea
      border = nameField.border
      visible = false
    }
    contents += (cardScrollPane, "grow, hmin 80, gapleft 3, gapright 3, wrap")

    // Buttons
    val cancelButton = new swing.Button {
      text = t("button.cancel")
    }
    listenTo(cancelButton)
    defaultButton = cancelButton
    val exportButton = new swing.Button {
      text = t("button.export")
      enabled = false
    }
    listenTo(exportButton)
    contents += (new BoxPanel(Orientation.Horizontal) {
      contents += cancelButton
      contents += exportButton
    }, "skip, span 2, right")


    reactions += {
      case ButtonClicked(`cancelButton`) =>
        debug("Cancelling deck export")
        ExportDeckBox.close()
      case ButtonClicked(`exportButton`) =>
        debug("Exporting deck")
        exportDeck()
    }

    def disableDeck() = {
      nameLabel.visible = false
      nameField.visible = false
      classLabel.visible = false
      classComboBox.visible = false
      cardLabel.visible = false
      cardScrollPane.visible = false
      exportButton.enabled = false
      resize(ExportDeckBox.minSizeWithoutDeck)
    }

    def enableDeck() = {
      nameLabel.visible = true
      nameField.visible = true
      classLabel.visible = true
      classComboBox.visible = true
      cardLabel.visible = true
      cardScrollPane.visible = true
      exportButton.enabled = true
      resize(ExportDeckBox.minSizeWithDeck)
    }
  }
  contents = panel

  HearthstoneAnalyser.addObserver(this)



  override def closeOperation {
    debug("Closing deck export window")
    ExportDeckBox.close()
  }


  // Observe the monitor class to determine which screen Hearthstone is on
  override def update(o: Observable, change: scala.Any): Unit = {
    change.asInstanceOf[AnalyserEvent] match {
      case AnalyserEvent.SCREEN =>
        setScreen(HearthstoneAnalyser.screen)
      case _ =>
    }
  }

  /**
   * Update status and instructions based on the screen currently being viewed in Hearthstone.
   * @param screen The current screen in Hearthstone
   */
  def setScreen(screen: Screen) = if (!hasDeck) screen match {
    case Screen.COLLECTION_DECK =>
      captureDeck()
    case Screen.COLLECTION | Screen.COLLECTION_ZOOM =>
      setStatus(t("export.status.not_ready"), t("export.instructions.no_deck"))
    case _ =>
      setStatus(t("export.status.not_ready"), t("export.instructions.no_collection"))
  }

  private def setStatus(s: String, i: String) = {
    panel.status.text = s
    panel.instructions.text = i
    pack()
  }

  private def resize(dimension: Dimension): Unit = {
    minimumSize = dimension
    preferredSize = dimension
    pack()
  }

  private def captureDeck(): Unit = {
    // Assume we have a deck until proved otherwise, this prevents events from resetting the status during the capture
    hasDeck = true

    setStatus(t("export.status.detecting"), t("export.instructions.detecting"))

    val hsHelper = monitor._hsHelper

//    val exportedDeck = ExtracterMain.exportOcr(img1, img2)
    var deck: Option[Deck] = None

    var attempts = 0;
    while (deck == None && attempts < 4) {
      attempts += 1
      debug(s"Attempt $attempts at identifying deck")

      hsHelper.bringWindowToForeground

      val robot = HsRobot(monitor._hsHelper.getHSWindowBounds)
      robot.collectionScrollAway()
      val img1 = hsHelper.getScreenCapture
      robot.collectionScrollTowards()
      val img2 = hsHelper.getScreenCapture

      val deckAnalyser = new DeckAnalyser(img1.getWidth, img1.getHeight)
      deck = deckAnalyser.identifyDeck(img1, img2)
    }

    peer.toFront()

    val deckString = deck match {
      case Some(d) =>
        // Successfully captured a deck
        setStatus(t("export.status.ready"), t("export.instructions.ready"))

        panel.nameField.text = d.name
        panel.classComboBox.selection.item = d.hero

        panel.enableDeck()
        d.deckString

      case None =>
        // Failed to capture a deck
        setStatus(t("export.status.error"), t("export.instructions.error"))
        Log.info("Could not export deck")
        hasDeck = false
        ""
    }

    panel.cardTextArea.text = deckString
  }

  private def exportDeck(): Unit = {
    // Attempt to convert the deck string back into card objects, which may fail if the string was edited badly
    val cards = Deck.parseDeckString(panel.cardTextArea.text.trim)
    val invalidCards = cards.filter(_.id == 0)

    if (invalidCards.length > 0) {
      // At least one card couldn't be recognised
      val details = invalidCards.map(_.name).mkString("\n")
      Main.showMessageDialog(this.peer, if (invalidCards.length == 1)
        s"Could not recognise this card:\n$details"
      else
        s"Could not recognise these cards:\n$details")
    } else {
      // All cards were recognised
      val deck = new Deck(
        name = panel.nameField.text.trim,
        cards = cards,
        hero = panel.classComboBox.selection.item)

      if (deck.isValid) {
        val jsonDeck = new JSONObject(collection.mutable.Map("deck" -> deck.toJsonObject))
        API.createDeck(jsonDeck) match {
          case true =>
            // Deck was loaded onto HearthStats.net successfully
            Main.showMessageDialog(peer, s"Deck ${deck.name} was exported to HearthStats.net successfully")
            ExportDeckBox.close()
          case false =>
            // An error occurred loading the deck onto HearthStats.net
            setStatus(t("export.status.error"), t("export.instructions.error"))
            peer.toFront()
        }
      } else {
        Main.showMessageDialog(this.peer, "Could not export because deck is invalid.\n" +
            s"Deck has ${deck.cardCount} cards, 30 are required.")
      }
    }

  }



}


object ExportDeckBox {

  val minSizeWithoutDeck = new Dimension(450, 200)
  val minSizeWithDeck = new Dimension(450, 400)
  val maxSize = new Dimension(450, 800)

  var currentWindow: Option[ExportDeckBox] = None

  def open(monitor: Monitor): ExportDeckBox = currentWindow match {
    // Redisplay existing window, if there is one
    case Some(box) => open(box)
    // Otherwise create a new window and display it
    case None => {
      currentWindow = Some(new ExportDeckBox(monitor))
      open(currentWindow.get)
    }
  }

  private def open(box: ExportDeckBox): ExportDeckBox = {
    box.open()
    box.pack()
    box.peer.toFront()
    // Set the initial status based on the current screen
    box.setScreen(HearthstoneAnalyser.screen)
    box
  }

  def close() = currentWindow match {
    case Some(box) =>
      box.close()
      box.dispose()
      HearthstoneAnalyser.deleteObserver(box)
      currentWindow = None
    case None =>
  }

}