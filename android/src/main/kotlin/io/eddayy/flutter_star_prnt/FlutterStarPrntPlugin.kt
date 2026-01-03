package io.eddayy.flutter_star_prnt

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.webkit.URLUtil
import androidx.annotation.NonNull
import com.starmicronics.stario.PortInfo
import com.starmicronics.stario.StarIOPort
import com.starmicronics.stario.StarPrinterStatus
import com.starmicronics.starioextension.ICommandBuilder
import com.starmicronics.starioextension.ICommandBuilder.*
import com.starmicronics.starioextension.IConnectionCallback
import com.starmicronics.starioextension.StarIoExt
import com.starmicronics.starioextension.StarIoExt.Emulation
import com.starmicronics.starioextension.StarIoExtManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException

/** FlutterStarPrntPlugin (v2 embedding only) */
class FlutterStarPrntPlugin : FlutterPlugin, MethodCallHandler {

  private var channel: MethodChannel? = null
  protected var starIoExtManager: StarIoExtManager? = null

  companion object {
    protected lateinit var applicationContext: Context

    @JvmStatic
    fun setupPlugin(messenger: BinaryMessenger, context: Context) {
      applicationContext = context.applicationContext
      val ch = MethodChannel(messenger, "flutter_star_prnt")
      ch.setMethodCallHandler(FlutterStarPrntPlugin())
    }
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    applicationContext = binding.applicationContext

    channel = MethodChannel(binding.binaryMessenger, "flutter_star_prnt")
    channel?.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel?.setMethodCallHandler(null)
    channel = null
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
    val result: Result = MethodResultWrapper(rawResult)
    Thread(MethodRunner(call, result)).start()
  }

  inner class MethodRunner(call: MethodCall, result: Result) : Runnable {
    private val call: MethodCall = call
    private val result: Result = result

    override fun run() {
      when (call.method) {
        "portDiscovery" -> portDiscovery(call, result)
        "checkStatus" -> checkStatus(call, result)
        "print" -> print(call, result)
        else -> result.notImplemented()
      }
    }
  }

  class MethodResultWrapper(methodResult: Result) : Result {
    private val methodResult: Result = methodResult
    private val handler: Handler = Handler(Looper.getMainLooper())

    override fun success(result: Any?) {
      handler.post { methodResult.success(result) }
    }

    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
      handler.post { methodResult.error(errorCode, errorMessage, errorDetails) }
    }

    override fun notImplemented() {
      handler.post { methodResult.notImplemented() }
    }
  }

  fun portDiscovery(@NonNull call: MethodCall, @NonNull result: Result) {
    val strInterface: String = call.argument<String>("type") as String
    try {
      val response: MutableList<Map<String, String>> =
        when (strInterface) {
          "LAN" -> getPortDiscovery("LAN")
          "Bluetooth" -> getPortDiscovery("Bluetooth")
          "USB" -> getPortDiscovery("USB")
          else -> getPortDiscovery("All")
        }
      result.success(response)
    } catch (e: Exception) {
      result.error("PORT_DISCOVERY_ERROR", e.message, null)
    }
  }

  fun checkStatus(@NonNull call: MethodCall, @NonNull result: Result) {
    val portName: String = call.argument<String>("portName") as String
    val emulation: String = call.argument<String>("emulation") as String

    var port: StarIOPort? = null
    try {
      val portSettings: String? = getPortSettingsOption(emulation)
      port = StarIOPort.getPort(portName, portSettings, 10000, applicationContext)

      try {
        Thread.sleep(500)
      } catch (_: InterruptedException) {}

      val status: StarPrinterStatus = port.retreiveStatus()

      val json: MutableMap<String, Any?> = mutableMapOf()
      json["is_success"] = true
      json["offline"] = status.offline
      json["coverOpen"] = status.coverOpen
      json["overTemp"] = status.overTemp
      json["cutterError"] = status.cutterError
      json["receiptPaperEmpty"] = status.receiptPaperEmpty

      try {
        val firmwareInformationMap: Map<String, String> = port.firmwareInformation
        json["ModelName"] = firmwareInformationMap["ModelName"]
        json["FirmwareVersion"] = firmwareInformationMap["FirmwareVersion"]
      } catch (e: Exception) {
        json["error_message"] = e.message
      }

      result.success(json)
    } catch (e: Exception) {
      result.error("CHECK_STATUS_ERROR", e.message, null)
    } finally {
      if (port != null) {
        try {
          StarIOPort.releasePort(port)
        } catch (e: Exception) {
          result.error("CHECK_STATUS_ERROR", e.message, null)
        }
      }
    }
  }

  // Not exposed currently (kept from old code)
  fun connect(@NonNull call: MethodCall, @NonNull result: Result) {
    val portName: String = call.argument<String>("portName") as String
    val emulation: String = call.argument<String>("emulation") as String
    val hasBarcodeReader: Boolean? = call.argument<Boolean>("hasBarcodeReader") as Boolean?

    val portSettings: String? = getPortSettingsOption(emulation)
    try {
      var mgr = this.starIoExtManager

      if (mgr?.port != null) {
        mgr.disconnect(object : IConnectionCallback {
          override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {}
          override fun onDisconnected() {}
        })
      }

      mgr =
        StarIoExtManager(
          if (hasBarcodeReader == true) StarIoExtManager.Type.WithBarcodeReader
          else StarIoExtManager.Type.Standard,
          portName,
          portSettings,
          10000,
          applicationContext
        )

      this.starIoExtManager = mgr

      mgr.connect(
        object : IConnectionCallback {
          override fun onConnected(connectResult: IConnectionCallback.ConnectResult) {
            if (connectResult == IConnectionCallback.ConnectResult.Success ||
              connectResult == IConnectionCallback.ConnectResult.AlreadyConnected
            ) {
              result.success("Printer Connected")
            } else {
              result.error("CONNECT_ERROR", "Error Connecting to the printer", null)
            }
          }

          override fun onDisconnected() {}
        }
      )
    } catch (e: Exception) {
      result.error("CONNECT_ERROR", e.message, e)
    }
  }

  fun print(@NonNull call: MethodCall, @NonNull result: Result) {
    val portName: String = call.argument<String>("portName") as String
    val emulation: String = call.argument<String>("emulation") as String
    val printCommands: ArrayList<Map<String, Any>> =
      call.argument<ArrayList<Map<String, Any>>>("printCommands") as ArrayList<Map<String, Any>>

    if (printCommands.isEmpty()) {
      val json: MutableMap<String, Any?> = mutableMapOf()
      json["offline"] = false
      json["coverOpen"] = false
      json["cutterError"] = false
      json["receiptPaperEmpty"] = false
      json["info_message"] = "No data to print"
      json["is_success"] = true
      result.success(json)
      return
    }

    val builder: ICommandBuilder = StarIoExt.createCommandBuilder(getEmulation(emulation))
    builder.beginDocument()
    appendCommands(builder, printCommands, applicationContext)
    builder.endDocument()

    sendCommand(
      portName,
      getPortSettingsOption(emulation),
      builder.commands,
      applicationContext,
      result
    )
  }

  private fun getPortDiscovery(@NonNull interfaceName: String): MutableList<Map<String, String>> {
    val arrayDiscovery: MutableList<PortInfo> = mutableListOf()
    val arrayPorts: MutableList<Map<String, String>> = mutableListOf()

    if (interfaceName == "Bluetooth" || interfaceName == "All") {
      for (portInfo in StarIOPort.searchPrinter("BT:")) {
        arrayDiscovery.add(portInfo)
      }
    }
    if (interfaceName == "LAN" || interfaceName == "All") {
      for (port in StarIOPort.searchPrinter("TCP:")) {
        arrayDiscovery.add(port)
      }
    }
    if (interfaceName == "USB" || interfaceName == "All") {
      try {
        for (port in StarIOPort.searchPrinter("USB:", applicationContext)) {
          arrayDiscovery.add(port)
        }
      } catch (e: Exception) {
        Log.e("FlutterStarPrnt", "usb not connected", e)
      }
    }

    for (discovery in arrayDiscovery) {
      val port: MutableMap<String, String> = mutableMapOf()

      if (discovery.portName.startsWith("BT:")) port["portName"] = "BT:" + discovery.macAddress
      else port["portName"] = discovery.portName

      if (discovery.macAddress != "") {
        port["macAddress"] = discovery.macAddress

        if (discovery.portName.startsWith("BT:")) {
          port["modelName"] = discovery.portName
        } else if (discovery.modelName != "") {
          port["modelName"] = discovery.modelName
        }
      } else if (interfaceName == "USB" || interfaceName == "All") {
        if (discovery.modelName != "") {
          port["modelName"] = discovery.modelName
        }
        if (discovery.usbSerialNumber != " SN:") {
          port["USBSerialNumber"] = discovery.usbSerialNumber
        }
      }

      arrayPorts.add(port)
    }

    return arrayPorts
  }

  private fun getPortSettingsOption(emulation: String): String {
    return when (emulation) {
      "EscPosMobile" -> "mini"
      "EscPos" -> "escpos"
      "StarPRNT", "StarPRNTL" -> "Portable;l"
      else -> emulation
    }
  }

  private fun getEmulation(emulation: String?): Emulation {
    return when (emulation) {
      "StarPRNT" -> Emulation.StarPRNT
      "StarPRNTL" -> Emulation.StarPRNTL
      "StarLine" -> Emulation.StarLine
      "StarGraphic" -> Emulation.StarGraphic
      "EscPos" -> Emulation.EscPos
      "EscPosMobile" -> Emulation.EscPosMobile
      "StarDotImpact" -> Emulation.StarDotImpact
      else -> Emulation.StarLine
    }
  }

  private fun appendCommands(
    builder: ICommandBuilder,
    printCommands: ArrayList<Map<String, Any>>?,
    context: Context
  ) {
    var encoding: Charset = Charset.forName("US-ASCII")

    printCommands?.forEach {
      if (it.containsKey("appendCharacterSpace"))
        builder.appendCharacterSpace(it["appendCharacterSpace"].toString().toInt())
      else if (it.containsKey("appendEncoding"))
        encoding = getEncoding(it["appendEncoding"].toString())
      else if (it.containsKey("appendCodePage"))
        builder.appendCodePage(getCodePageType(it["appendCodePage"].toString()))
      else if (it.containsKey("append"))
        builder.append(it["append"].toString().toByteArray(encoding))
      else if (it.containsKey("appendRaw"))
        builder.append(it["appendRaw"].toString().toByteArray(encoding))
      else if (it.containsKey("appendMultiple"))
        builder.appendMultiple(it["appendMultiple"].toString().toByteArray(encoding), 2, 2)
      else if (it.containsKey("appendEmphasis"))
        builder.appendEmphasis(it["appendEmphasis"].toString().toByteArray(encoding))
      else if (it.containsKey("enableEmphasis"))
        builder.appendEmphasis(it["enableEmphasis"].toString().toBoolean())
      else if (it.containsKey("appendInvert"))
        builder.appendInvert(it["appendInvert"].toString().toByteArray(encoding))
      else if (it.containsKey("enableInvert"))
        builder.appendInvert(it["enableInvert"].toString().toBoolean())
      else if (it.containsKey("appendUnderline"))
        builder.appendUnderLine(it["appendUnderline"].toString().toByteArray(encoding))
      else if (it.containsKey("enableUnderline"))
        builder.appendUnderLine(it["enableUnderline"].toString().toBoolean())
      else if (it.containsKey("appendInternational"))
        builder.appendInternational(getInternational(it["appendInternational"].toString()))
      else if (it.containsKey("appendLineFeed"))
        builder.appendLineFeed(it["appendLineFeed"] as Int)
      else if (it.containsKey("appendUnitFeed"))
        builder.appendUnitFeed(it["appendUnitFeed"] as Int)
      else if (it.containsKey("appendLineSpace"))
        builder.appendLineSpace(it["appendLineSpace"] as Int)
      else if (it.containsKey("appendFontStyle"))
        builder.appendFontStyle(getFontStyle(it["appendFontStyle"] as String))
      else if (it.containsKey("appendCutPaper"))
        builder.appendCutPaper(getCutPaperAction(it["appendCutPaper"].toString()))
      else if (it.containsKey("openCashDrawer"))
        builder.appendPeripheral(getPeripheralChannel(it["openCashDrawer"] as Int))
      else if (it.containsKey("appendBlackMark"))
        builder.appendBlackMark(getBlackMarkType(it["appendBlackMark"].toString()))
      else if (it.containsKey("appendBytes"))
        builder.append(it["appendBytes"].toString().toByteArray(encoding))
      else if (it.containsKey("appendRawBytes"))
        builder.appendRaw(it["appendRawBytes"].toString().toByteArray(encoding))
      else if (it.containsKey("appendAbsolutePosition")) {
        if (it.containsKey("data"))
          builder.appendAbsolutePosition(
            it["data"].toString().toByteArray(encoding),
            it["appendAbsolutePosition"].toString().toInt()
          )
        else builder.appendAbsolutePosition(it["appendAbsolutePosition"].toString().toInt())
      } else if (it.containsKey("appendAlignment")) {
        if (it.containsKey("data"))
          builder.appendAlignment(
            it["data"].toString().toByteArray(encoding),
            getAlignment(it["appendAlignment"].toString())
          )
        else builder.appendAlignment(getAlignment(it["appendAlignment"].toString()))
      } else if (it.containsKey("appendHorizontalTabPosition"))
        builder.appendHorizontalTabPosition(it["appendHorizontalTabPosition"] as IntArray)
      else if (it.containsKey("appendLogo")) {
        if (it.containsKey("logoSize"))
          builder.appendLogo(getLogoSize(it["logoSize"] as String), it["appendLogo"] as Int)
        else builder.appendLogo(getLogoSize("Normal"), it["appendLogo"] as Int)
      } else if (it.containsKey("appendBarcode")) {
        val barcodeSymbology: BarcodeSymbology =
          if (it.containsKey("BarcodeSymbology")) getBarcodeSymbology(it["BarcodeSymbology"].toString())
          else getBarcodeSymbology("Code128")
        val barcodeWidth: BarcodeWidth =
          if (it.containsKey("BarcodeWidth")) getBarcodeWidth(it["BarcodeWidth"].toString())
          else getBarcodeWidth("Mode2")
        val height: Int = if (it.containsKey("height")) it["height"].toString().toInt() else 40
        val hri: Boolean = if (it.containsKey("hri")) it["hri"].toString().toBoolean() else true

        if (it.containsKey("absolutePosition")) {
          builder.appendBarcodeWithAbsolutePosition(
            it["appendBarcode"].toString().toByteArray(encoding),
            barcodeSymbology,
            barcodeWidth,
            height,
            hri,
            it["absolutePosition"] as Int
          )
        } else if (it.containsKey("alignment")) {
          builder.appendBarcodeWithAlignment(
            it["appendBarcode"].toString().toByteArray(encoding),
            barcodeSymbology,
            barcodeWidth,
            height,
            hri,
            getAlignment(it["alignment"].toString())
          )
        } else {
          builder.appendBarcode(
            it["appendBarcode"].toString().toByteArray(encoding),
            barcodeSymbology,
            barcodeWidth,
            height,
            hri
          )
        }
      } else if (it.containsKey("appendBitmap")) {
        val diffusion: Boolean = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
        val width: Int = if (it.containsKey("width")) it["width"].toString().toInt() else 576
        val bothScale: Boolean = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
        val rotation: BitmapConverterRotation =
          if (it.containsKey("rotation")) getConverterRotation(it["rotation"].toString())
          else getConverterRotation("Normal")

        try {
          val bitmap: Bitmap? =
            if (URLUtil.isValidUrl(it["appendBitmap"].toString())) {
              val imageUri: Uri = Uri.parse(it["appendBitmap"].toString())
              MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            } else {
              BitmapFactory.decodeFile(it["appendBitmap"].toString())
            }

          if (bitmap != null) {
            if (it.containsKey("absolutePosition")) {
              builder.appendBitmapWithAbsolutePosition(
                bitmap, diffusion, width, bothScale, rotation,
                it["absolutePosition"].toString().toInt()
              )
            } else if (it.containsKey("alignment")) {
              builder.appendBitmapWithAlignment(
                bitmap, diffusion, width, bothScale, rotation,
                getAlignment(it["alignment"].toString())
              )
            } else {
              builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
            }
          }
        } catch (e: Exception) {
          Log.e("FlutterStarPrnt", "appendBitmap failed", e)
        }
      } else if (it.containsKey("appendBitmapText")) {
        val fontSize: Float = if (it.containsKey("fontSize")) it["fontSize"].toString().toFloat() else 25f
        val diffusion: Boolean = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
        val width: Int = if (it.containsKey("width")) it["width"].toString().toInt() else 576
        val bothScale: Boolean = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
        val text: String = it["appendBitmapText"].toString()
        val typeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        val bitmap: Bitmap = createBitmapFromText(text, fontSize, width, typeface)
        val rotation: BitmapConverterRotation =
          if (it.containsKey("rotation")) getConverterRotation(it["rotation"].toString())
          else getConverterRotation("Normal")

        if (it.containsKey("absolutePosition")) {
          builder.appendBitmapWithAbsolutePosition(
            bitmap, diffusion, width, bothScale, rotation,
            it["absolutePosition"] as Int
          )
        } else if (it.containsKey("alignment")) {
          builder.appendBitmapWithAlignment(
            bitmap, diffusion, width, bothScale, rotation,
            getAlignment(it["alignment"].toString())
          )
        } else {
          builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
        }
      } else if (it.containsKey("appendBitmapByteArray")) {
        val diffusion: Boolean = if (it.containsKey("diffusion")) it["diffusion"].toString().toBoolean() else true
        val width: Int = if (it.containsKey("width")) it["width"].toString().toInt() else 576
        val bothScale: Boolean = if (it.containsKey("bothScale")) it["bothScale"].toString().toBoolean() else true
        val rotation: BitmapConverterRotation =
          if (it.containsKey("rotation")) getConverterRotation(it["rotation"].toString())
          else getConverterRotation("Normal")

        try {
          val byteArray: ByteArray = it["appendBitmapByteArray"] as ByteArray
          val bitmap: Bitmap? = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

          if (bitmap != null) {
            if (it.containsKey("absolutePosition")) {
              builder.appendBitmapWithAbsolutePosition(
                bitmap, diffusion, width, bothScale, rotation,
                it["absolutePosition"].toString().toInt()
              )
            } else if (it.containsKey("alignment")) {
              builder.appendBitmapWithAlignment(
                bitmap, diffusion, width, bothScale, rotation,
                getAlignment(it["alignment"].toString())
              )
            } else {
              builder.appendBitmap(bitmap, diffusion, width, bothScale, rotation)
            }
          }
        } catch (e: Exception) {
          Log.e("FlutterStarPrnt", "appendBitmapByteArray failed", e)
        }
      }
    }
  }

  private fun getEncoding(encoding: String): Charset {
    return when (encoding) {
      "US-ASCII" -> Charset.forName("US-ASCII")
      "Windows-1252" -> try { Charset.forName("Windows-1252") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
      "Shift-JIS" -> try { Charset.forName("Shift-JIS") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
      "Windows-1251" -> try { Charset.forName("Windows-1251") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
      "GB2312" -> try { Charset.forName("GB2312") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
      "Big5" -> try { Charset.forName("Big5") } catch (_: UnsupportedCharsetException) { Charset.forName("UTF-8") }
      "UTF-8" -> Charset.forName("UTF-8")
      else -> Charset.forName("US-ASCII")
    }
  }

  private fun getCodePageType(codePageType: String): ICommandBuilder.CodePageType {
    return when (codePageType) {
      "CP437" -> CodePageType.CP437
      "CP737" -> CodePageType.CP737
      "CP772" -> CodePageType.CP772
      "CP774" -> CodePageType.CP774
      "CP851" -> CodePageType.CP851
      "CP852" -> CodePageType.CP852
      "CP855" -> CodePageType.CP855
      "CP857" -> CodePageType.CP857
      "CP858" -> CodePageType.CP858
      "CP860" -> CodePageType.CP860
      "CP861" -> CodePageType.CP861
      "CP862" -> CodePageType.CP862
      "CP863" -> CodePageType.CP863
      "CP864" -> CodePageType.CP864
      "CP865" -> CodePageType.CP865
      "CP869" -> CodePageType.CP869
      "CP874" -> CodePageType.CP874
      "CP928" -> CodePageType.CP928
      "CP932" -> CodePageType.CP932
      "CP999" -> CodePageType.CP999
      "CP1001" -> CodePageType.CP1001
      "CP1250" -> CodePageType.CP1250
      "CP1251" -> CodePageType.CP1251
      "CP1252" -> CodePageType.CP1252
      "CP2001" -> CodePageType.CP2001
      "CP3001" -> CodePageType.CP3001
      "CP3002" -> CodePageType.CP3002
      "CP3011" -> CodePageType.CP3011
      "CP3012" -> CodePageType.CP3012
      "CP3021" -> CodePageType.CP3021
      "CP3041" -> CodePageType.CP3041
      "CP3840" -> CodePageType.CP3840
      "CP3841" -> CodePageType.CP3841
      "CP3843" -> CodePageType.CP3843
      "CP3845" -> CodePageType.CP3845
      "CP3846" -> CodePageType.CP3846
      "CP3847" -> CodePageType.CP3847
      "CP3848" -> CodePageType.CP3848
      "UTF8" -> CodePageType.UTF8
      "Blank" -> CodePageType.Blank
      else -> CodePageType.CP998
    }
  }

  private fun getInternational(international: String): ICommandBuilder.InternationalType {
    return when (international) {
      "UK" -> ICommandBuilder.InternationalType.UK
      "USA" -> ICommandBuilder.InternationalType.USA
      "France" -> ICommandBuilder.InternationalType.France
      "Germany" -> ICommandBuilder.InternationalType.Germany
      "Denmark" -> ICommandBuilder.InternationalType.Denmark
      "Sweden" -> ICommandBuilder.InternationalType.Sweden
      "Italy" -> ICommandBuilder.InternationalType.Italy
      "Spain" -> ICommandBuilder.InternationalType.Spain
      "Japan" -> ICommandBuilder.InternationalType.Japan
      "Norway" -> ICommandBuilder.InternationalType.Norway
      "Denmark2" -> ICommandBuilder.InternationalType.Denmark2
      "Spain2" -> ICommandBuilder.InternationalType.Spain2
      "LatinAmerica" -> ICommandBuilder.InternationalType.LatinAmerica
      "Korea" -> ICommandBuilder.InternationalType.Korea
      "Ireland" -> ICommandBuilder.InternationalType.Ireland
      "Legal" -> ICommandBuilder.InternationalType.Legal
      else -> ICommandBuilder.InternationalType.USA
    }
  }

  private fun getFontStyle(fontStyle: String): ICommandBuilder.FontStyleType {
    return when (fontStyle) {
      "A" -> ICommandBuilder.FontStyleType.A
      "B" -> ICommandBuilder.FontStyleType.B
      else -> ICommandBuilder.FontStyleType.A
    }
  }

  private fun getCutPaperAction(cutPaperAction: String): ICommandBuilder.CutPaperAction {
    return when (cutPaperAction) {
      "FullCut" -> CutPaperAction.FullCut
      "FullCutWithFeed" -> CutPaperAction.FullCutWithFeed
      "PartialCut" -> CutPaperAction.PartialCut
      "PartialCutWithFeed" -> CutPaperAction.PartialCutWithFeed
      else -> CutPaperAction.PartialCutWithFeed
    }
  }

  private fun getPeripheralChannel(peripheralChannel: Int): ICommandBuilder.PeripheralChannel {
    return when (peripheralChannel) {
      1 -> ICommandBuilder.PeripheralChannel.No1
      2 -> ICommandBuilder.PeripheralChannel.No2
      else -> ICommandBuilder.PeripheralChannel.No1
    }
  }

  private fun getBlackMarkType(blackMarkType: String): ICommandBuilder.BlackMarkType {
    return when (blackMarkType) {
      "Valid" -> ICommandBuilder.BlackMarkType.Valid
      "Invalid" -> ICommandBuilder.BlackMarkType.Invalid
      "ValidWithDetection" -> ICommandBuilder.BlackMarkType.ValidWithDetection
      else -> ICommandBuilder.BlackMarkType.Valid
    }
  }

  private fun getAlignment(alignment: String): ICommandBuilder.AlignmentPosition {
    return when (alignment) {
      "Left" -> ICommandBuilder.AlignmentPosition.Left
      "Center" -> ICommandBuilder.AlignmentPosition.Center
      "Right" -> ICommandBuilder.AlignmentPosition.Right
      else -> ICommandBuilder.AlignmentPosition.Left
    }
  }

  private fun getLogoSize(logoSize: String): ICommandBuilder.LogoSize {
    return when (logoSize) {
      "Normal" -> ICommandBuilder.LogoSize.Normal
      "DoubleWidth" -> ICommandBuilder.LogoSize.DoubleWidth
      "DoubleHeight" -> ICommandBuilder.LogoSize.DoubleHeight
      "DoubleWidthDoubleHeight" -> ICommandBuilder.LogoSize.DoubleWidthDoubleHeight
      else -> ICommandBuilder.LogoSize.Normal
    }
  }

  private fun getBarcodeSymbology(barcodeSymbology: String): ICommandBuilder.BarcodeSymbology {
    return when (barcodeSymbology) {
      "Code128" -> ICommandBuilder.BarcodeSymbology.Code128
      "Code39" -> ICommandBuilder.BarcodeSymbology.Code39
      "Code93" -> ICommandBuilder.BarcodeSymbology.Code93
      "ITF" -> ICommandBuilder.BarcodeSymbology.ITF
      "JAN8" -> ICommandBuilder.BarcodeSymbology.JAN8
      "JAN13" -> ICommandBuilder.BarcodeSymbology.JAN13
      "NW7" -> ICommandBuilder.BarcodeSymbology.NW7
      "UPCA" -> ICommandBuilder.BarcodeSymbology.UPCA
      "UPCE" -> ICommandBuilder.BarcodeSymbology.UPCE
      else -> ICommandBuilder.BarcodeSymbology.Code128
    }
  }

  private fun getBarcodeWidth(barcodeWidth: String): ICommandBuilder.BarcodeWidth {
    return when (barcodeWidth) {
      "Mode1" -> ICommandBuilder.BarcodeWidth.Mode1
      "Mode2" -> ICommandBuilder.BarcodeWidth.Mode2
      "Mode3" -> ICommandBuilder.BarcodeWidth.Mode3
      "Mode4" -> ICommandBuilder.BarcodeWidth.Mode4
      "Mode5" -> ICommandBuilder.BarcodeWidth.Mode5
      "Mode6" -> ICommandBuilder.BarcodeWidth.Mode6
      "Mode7" -> ICommandBuilder.BarcodeWidth.Mode7
      "Mode8" -> ICommandBuilder.BarcodeWidth.Mode8
      "Mode9" -> ICommandBuilder.BarcodeWidth.Mode9
      else -> ICommandBuilder.BarcodeWidth.Mode2
    }
  }

  private fun getConverterRotation(converterRotation: String): ICommandBuilder.BitmapConverterRotation {
    return when (converterRotation) {
      "Normal" -> ICommandBuilder.BitmapConverterRotation.Normal
      "Left90" -> ICommandBuilder.BitmapConverterRotation.Left90
      "Right90" -> ICommandBuilder.BitmapConverterRotation.Right90
      "Rotate180" -> ICommandBuilder.BitmapConverterRotation.Rotate180
      else -> ICommandBuilder.BitmapConverterRotation.Normal
    }
  }

  private fun createBitmapFromText(
    printText: String,
    textSize: Float,
    printWidth: Int,
    typeface: Typeface
  ): Bitmap {
    val paint = Paint()
    paint.textSize = textSize
    paint.typeface = typeface
    paint.getTextBounds(printText, 0, printText.length, Rect())

    val textPaint = TextPaint(paint)
    val staticLayout =
      StaticLayout(
        printText,
        textPaint,
        printWidth,
        Layout.Alignment.ALIGN_NORMAL,
        1f,
        0f,
        false
      )

    val bitmap = Bitmap.createBitmap(staticLayout.width, staticLayout.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    canvas.translate(0f, 0f)
    staticLayout.draw(canvas)
    return bitmap
  }

  private fun sendCommand(
    portName: String,
    portSettings: String,
    commands: ByteArray,
    context: Context,
    @NonNull result: Result
  ) {
    var port: StarIOPort? = null
    var errorPosString = ""
    try {
      port = StarIOPort.getPort(portName, portSettings, 10000, applicationContext)
      errorPosString += "Port Opened,"

      try {
        Thread.sleep(100)
      } catch (_: InterruptedException) {}

      var status: StarPrinterStatus = port.beginCheckedBlock()
      val json: MutableMap<String, Any?> = mutableMapOf()
      errorPosString += "got status for beginCheck,"

      json["offline"] = status.offline
      json["coverOpen"] = status.coverOpen
      json["cutterError"] = status.cutterError
      json["receiptPaperEmpty"] = status.receiptPaperEmpty

      var isSuccess = true
      if (status.offline) {
        json["error_message"] = "A printer is offline"
        isSuccess = false
      } else if (status.coverOpen) {
        json["error_message"] = "Printer cover is open"
        isSuccess = false
      } else if (status.receiptPaperEmpty) {
        json["error_message"] = "Paper empty"
        isSuccess = false
      } else if (status.presenterPaperJamError) {
        json["error_message"] = "Paper Jam"
        isSuccess = false
      }

      if (status.receiptPaperNearEmptyInner || status.receiptPaperNearEmptyOuter) {
        json["error_message"] = "Paper near empty"
      }

      if (isSuccess) {
        errorPosString += "Writing to port,"
        port.writePort(commands, 0, commands.size)

        errorPosString += "End checked block,"
        port.setEndCheckedBlockTimeoutMillis(30000)
        try {
          status = port.endCheckedBlock()
        } catch (e: Exception) {
          errorPosString += "EndCheckedBlock exception ${e},"
        }

        json["offline"] = status.offline
        json["coverOpen"] = status.coverOpen
        json["cutterError"] = status.cutterError
        json["receiptPaperEmpty"] = status.receiptPaperEmpty

        if (status.offline) {
          json["error_message"] = "A printer is offline"
          isSuccess = false
        } else if (status.coverOpen) {
          json["error_message"] = "Printer cover is open"
          isSuccess = false
        } else if (status.receiptPaperEmpty) {
          json["error_message"] = "Paper empty"
          isSuccess = false
        } else if (status.presenterPaperJamError) {
          json["error_message"] = "Paper Jam"
          isSuccess = false
        }
      }

      json["is_success"] = isSuccess
      result.success(json)
    } catch (e: Exception) {
      result.error("STARIO_PORT_EXCEPTION", (e.message ?: "") + " Failed After $errorPosString", null)
    } finally {
      if (port != null) {
        try {
          StarIOPort.releasePort(port)
        } catch (_: Exception) {}
      }
    }
  }
}
