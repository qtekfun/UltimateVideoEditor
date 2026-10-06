package com.ultimatevideo.uveditor.data.interchange

/**
 * Hand-written, sanitised LumaFusion archives in the structure of the files LumaFusion for iOS 5.5.2 writes (names, ids and
 * paths are invented). Times are CMTimes at timescale 600 unless said otherwise.
 */
object LfFixture {
    fun time(value: Long, scale: Long = 600, flags: Int = 1) = """{"epoch":0,"flags":$flags,"timescale":$scale,"value":$value}"""

    private fun attr(value: String, type: Int = 2, keyed: Boolean = false) =
        """{"conversionType":0,"identityValue":null,"keyframes":${if (keyed) """[{"time":${time(0)},"value":$value}]""" else "[]"},"value":{"type":$type,"value":$value}}"""

    private fun point(x: Double, y: Double) = """{"path":[{"interpolation":0,"type":2,"value":[0,0]},{"interpolation":0,"type":3,"value":[0,0]}],"point":[$x,$y]}"""

    private fun range(start: Long, duration: Long) = """{"duration":${time(duration)},"start":${time(start)}}"""

    class ClipSpec(
        val id: String,
        val file: String,
        val start: Long,
        val duration: Long,
        val sourceStart: Long = 0,
        /** 0 video file, 2 photo, 4 title. */
        val type: Int = 0,
        val video: Boolean = true,
        val audio: Boolean = true,
        val speed: Double = 1.0,
        val reversed: Boolean = false,
        val transition: Int = 0,
        val effects: List<String> = emptyList(),
        val alpha: Double = 1.0,
        val volume: Double = 1.0,
        val pan: Double = 0.0,
        val scale: String = "[1,1]",
        val translation: Pair<Double, Double> = 0.0 to 0.0,
        val rotation: Double = 0.0,
        val orientation: Double = 0.0,
        val flipH: Boolean = false,
        val keyedAlpha: Boolean = false,
        val assetKey: String = "asset-$file",
        val runtimeTitle: String? = null,
        val original: String? = null,
    )

    fun clip(c: ClipSpec): String {
        val visualType = when (c.type) { 2 -> 2; 4 -> 3; else -> 0 }
        val effectsJson = c.effects.joinToString(",", "[", "]") { """{"displayName":"$it","effectID":"fx-$it","effectName":"BuiltIn"}""" }
        val videoStream = if (c.video || c.type != 0) """{"assetTrackID":1,"effects":$effectsJson,"mediaType":$visualType,
            "fullSourceRange":${range(c.sourceStart, c.duration)},"sourceRange":${range(c.sourceStart, c.duration)},"sourceStart":${time(c.sourceStart)},
            "streamAttributes":{
              "speed":${attr(c.speed.toString())},"videoAlpha":${attr(c.alpha.toString(), keyed = c.keyedAlpha)},
              "videoAnchor":${attr(point(0.0, 0.0), 13)},"videoBlendMode":${attr("0", 1)},"videoCrop":${attr("[[0,0],[1,1]]", 9)},
              "videoFitMode":${attr("1", 1)},"videoFlipH":${attr(c.flipH.toString(), 0)},"videoFlipV":${attr("false", 0)},
              "videoOrientation":${attr(c.orientation.toString())},"videoRotation":${attr(c.rotation.toString())},"videoScale":${attr(c.scale, 14)},
              "videoTranslation":${attr(point(c.translation.first, c.translation.second), 13)}}}""" else null
        val audioStream = if (c.audio && c.type == 0) """{"assetTrackID":2,"effects":[],"mediaType":1,
            "fullSourceRange":${range(c.sourceStart, c.duration)},"sourceRange":${range(c.sourceStart, c.duration)},"sourceStart":${time(c.sourceStart)},
            "streamAttributes":{"audioDuck":${attr("0", 1)},"audioFill":${attr("0", 1)},"audioPan":${attr(c.pan.toString())},
              "audioVolume":${attr(c.volume.toString())},"speed":${attr(c.speed.toString())}}}""" else null
        val streams = listOfNotNull(audioStream, videoStream).joinToString(",", "[", "]")
        val title = if (c.runtimeTitle != null) ""","runtimeTitle":${c.runtimeTitle}""" else ""
        return """{"anchorFromClipIDs":[],"assetIsBlank":false,"assetType":${c.type},"assetURL":"file:///private/${c.file}",
            "attributes":{"assetID":"${c.assetKey}","originalFilename":"${c.original ?: "/Volumes/Disk/Folder/${c.file}"}","title":"${c.file}","naturalSize":[3840,2160]},
            "clipID":"${c.id}","reversed":${c.reversed},"title":"${c.file}","trackStart":${time(c.start)},"trackDuration":${time(c.duration)},
            "transitionType":${c.transition},"streams":$streams$title}"""
    }

    fun track(type: Int, offset: Int, clips: List<ClipSpec>, anchor: Boolean = false, hidden: Boolean = false, volume: Double = 1.0) =
        """{"clips":[${clips.joinToString(",") { clip(it) }}],"hidden":$hidden,"isAnchorTrack":$anchor,"locked":false,"title":"",
            "trackOffset":$offset,"trackType":$type,"trackVolume":$volume,"trackID":"t$type$offset"}"""

    /** A title like LumaFusion's lower third: a translucent black box and a centred white text, in a 3840 x 2160 frame. */
    val lowerThird = """{"fileArray":[],"frameSize":[3840,2160],"layers":[
        {"hidden":false,"layerOpacity":0.35,"layerRect":[[104.0,1600.0],[3632.0,444.0]],"layerRotation":0,"name":"Box","opacity":${attr("1")},
         "rotation":${attr("0")},"scale":${attr("[1,1]", 14)},"shadowAttributes":{"shadowBlurRadius":0,"shadowOffset":[0,0]},
         "shapeAttributes":{"runs":[{"attributes":{"foregroundColor":"0.0 0.0 0.0 1.0"},"length":5,"start":0}],"string":"shape"},
         "shapePath":{"elements":[{"type":"moveTo","x":0,"y":0},{"type":"lineTo","x":1,"y":0},{"type":"closePath"}]},
         "text":{"string":null},"translation":${attr(point(0.0, 0.0), 13)},"type":0},
        {"hidden":false,"layerOpacity":1,"layerRect":[[270.0,1616.0],[3300.0,414.0]],"layerRotation":0,"name":"Hello","opacity":${attr("1")},
         "rotation":${attr("0")},"scale":${attr("[1,1]", 14)},"shadowAttributes":{"shadowBlurRadius":0,"shadowOffset":[5.5,0]},
         "shapePath":{"properties":null},"translation":${attr(point(0.0, 0.0), 13)},"type":1,
         "text":{"runs":[{"attributes":{"font":{"fontName":"ArialMT","pointSize":178.125},"foregroundColor":"1.0 1.0 1.0 1.0",
            "paragraphStyle":{"alignment":1}},"length":5,"start":0}],"string":"Hello"}}]}"""

    /**
     * An archive. [stepScale]/[stepValue] is the frame step (10/600 = 60 fps); [tracks] are track JSON strings from [track].
     */
    fun archive(
        tracks: List<String>,
        title: String = "Test project",
        stepValue: Long = 10,
        stepScale: Long = 600,
        resolution: String = "[1920,1080]",
        extra: String = "",
        markers: String = "[]",
        colorspace: Int = 0,
    ) = """{"attributes":{"appVersion":"5.5.2.0","compositionMarkers":$markers,"trackMarkers":{},"assetMarkers":{},"notes":""},
        "backgroundColor":"0.0 0.0 0.0 1.0","primaryVolume":1,"resolution":$resolution,"stepTime":${time(stepValue, stepScale)},
        "title":"$title","videoColorspace":$colorspace,"needsCloudMedia":false,"tracks":[${tracks.joinToString(",")}]$extra}"""
}
