import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_star_prnt/src/enums.dart';

class PrintCommands {
  List<Map<String, dynamic>> _commands = [];

  List<Map<String, dynamic>> getCommands() => _commands;

  appendEncoding(StarEncoding encoding) {
    _commands.add({"appendEncoding": encoding.text});
  }

  appendCutPaper(StarCutPaperAction action) {
    _commands.add({"appendCutPaper": action.text});
  }

  openCashDrawer(int actionNumber) {
    _commands.add({"openCashDrawer": actionNumber});
  }

  appendBitmap({
    required String path,
    bool diffusion = true,
    int width = 576,
    bool bothScale = true,
    int? absolutePosition,
    StarAlignmentPosition? alignment,
    StarBitmapConverterRotation? rotation,
  }) {
    final Map<String, dynamic> command = {
      "appendBitmap": path,
      "bothScale": bothScale,
      "diffusion": diffusion,
      "width": width,
    };
    if (absolutePosition != null) command['absolutePosition'] = absolutePosition;
    if (alignment != null) command['alignment'] = alignment.text;
    if (rotation != null) command['rotation'] = rotation.text;
    _commands.add(command);
  }

  appendBitmapByte({
    required Uint8List byteData,
    bool diffusion = true,
    int width = 576,
    bool bothScale = true,
    int? absolutePosition,
    StarAlignmentPosition? alignment,
    StarBitmapConverterRotation? rotation,
  }) {
    final Map<String, dynamic> command = {
      "appendBitmapByteArray": byteData,
      "bothScale": bothScale,
      "diffusion": diffusion,
      "width": width,
    };
    if (absolutePosition != null) command['absolutePosition'] = absolutePosition;
    if (alignment != null) command['alignment'] = alignment.text;
    if (rotation != null) command['rotation'] = rotation.text;
    _commands.add(command);
  }

  appendBitmapWidget({
    required BuildContext context,
    required Widget widget,
    bool diffusion = true,
    int width = 576,
    bool bothScale = true,
    int? absolutePosition,
    StarAlignmentPosition? alignment,
    StarBitmapConverterRotation? rotation,
    Duration? wait,
    Size? logicalSize,
    Size? imageSize,
    TextDirection textDirection = TextDirection.ltr,
  }) {
    createImageFromWidget(
      context,
      widget,
      wait: wait,
      logicalSize: logicalSize,
      imageSize: imageSize,
      textDirection: textDirection,
    ).then((byte) {
      if (byte != null) {
        appendBitmapByte(
          byteData: byte,
          diffusion: diffusion,
          width: width,
          bothScale: bothScale,
          absolutePosition: absolutePosition,
          alignment: alignment,
          rotation: rotation,
        );
      }
    });
  }

  appendBitmapText({
    required String text,
    int? fontSize,
    bool diffusion = true,
    int? width,
    bool bothScale = true,
    int? absolutePosition,
    StarAlignmentPosition? alignment,
    StarBitmapConverterRotation? rotation,
  }) {
    final Map<String, dynamic> command = {
      "appendBitmapText": text,
      "bothScale": bothScale,
      "diffusion": diffusion,
    };
    if (fontSize != null) command['fontSize'] = fontSize;
    if (width != null) command['width'] = width;
    if (absolutePosition != null) command['absolutePosition'] = absolutePosition;
    if (alignment != null) command['alignment'] = alignment.text;
    if (rotation != null) command['rotation'] = rotation.text;
    _commands.add(command);
  }

  push(Map<String, dynamic> command) => _commands.add(command);
  clear() => _commands.clear();

  static Future<Uint8List?> createImageFromWidget(
    BuildContext context,
    Widget widget, {
    Duration? wait,
    Size? logicalSize,
    Size? imageSize,
    TextDirection textDirection = TextDirection.ltr,
  }) async {
    final RenderRepaintBoundary repaintBoundary = RenderRepaintBoundary();
    final ui.FlutterView flutterView = View.of(context);
    final double pixelRatio = flutterView.devicePixelRatio;

    final Size finalLogicalSize = logicalSize ?? (flutterView.physicalSize / pixelRatio);
    final Size finalImageSize = imageSize ?? Size(
      finalLogicalSize.width * pixelRatio,
      finalLogicalSize.height * pixelRatio,
    );

    final RenderView renderView = RenderView(
      view: flutterView,
      child: RenderPositionedBox(
        alignment: Alignment.center,
        child: repaintBoundary,
      ),
      configuration: ViewConfiguration(
        logicalConstraints: BoxConstraints.tight(finalLogicalSize),
        physicalConstraints: BoxConstraints.tight(finalImageSize),
        devicePixelRatio: pixelRatio,
      ),
    );

    final PipelineOwner pipelineOwner = PipelineOwner();
    final BuildOwner buildOwner = BuildOwner(focusManager: FocusManager());

    pipelineOwner.rootNode = renderView;
    renderView.prepareInitialFrame();

    final RenderObjectToWidgetElement<RenderBox> rootElement =
        RenderObjectToWidgetAdapter<RenderBox>(
      container: repaintBoundary,
      child: Directionality(
        textDirection: textDirection,
        child: IntrinsicHeight(child: IntrinsicWidth(child: widget)),
      ),
    ).attachToRenderTree(buildOwner);

    buildOwner.buildScope(rootElement);
    if (wait != null) await Future.delayed(wait);
    buildOwner..buildScope(rootElement)..finalizeTree();

    pipelineOwner..flushLayout()..flushCompositingBits()..flushPaint();

    final ui.Image image = await repaintBoundary.toImage(
      pixelRatio: finalImageSize.width / finalLogicalSize.width,
    );

    final ByteData? byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    return byteData?.buffer.asUint8List();
  }
}
