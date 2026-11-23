package app.callscribe.providers.ondevice

/** One file of a downloadable model, pinned by size and SHA-256 so a corrupt or swapped file is rejected. */
data class ModelFile(val path: String, val bytes: Long, val sha256: String) {
    /** Name on the phone (flat directory per model). */
    val localName: String get() = path.substringAfterLast('/')
}

/**
 * A speech model the user can download to the phone. Files come from a Hugging Face repo at a pinned commit,
 * so the download never changes under us.
 */
data class OnDeviceModel(
    val id: String,
    val title: String,
    val repo: String,
    val revision: String,
    val license: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun url(f: ModelFile): String = "https://huggingface.co/$repo/resolve/$revision/${f.path}"

    fun file(name: String): ModelFile = files.first { it.localName == name }
}

object OnDeviceModels {
    /**
     * ivrit.ai's Hebrew streaming Zipformer transducer (66M params, int8) for sherpa-onnx — the model the ivrit.ai app
     * uses for live transcription and on phones too weak for Whisper. WER (model card): eval-d1 5.3 %, eval-whatsapp 8.8 %.
     */
    val ZIPFORMER_HE = OnDeviceModel(
        id = "zipformer2-66m-streaming-he",
        title = "ivrit.ai Zipformer (Hebrew, 66M, int8)",
        repo = "ivrit-ai/zipformer2-66m-streaming-he",
        revision = "ee769a2a13b887f91572eec349458bfb0fe79e7a",
        license = "CC-BY-4.0",
        files = listOf(
            ModelFile("sherpa-onnx/encoder.int8.onnx", 70_478_964, "6c6febcb74a09435315a1d872a6539f6e7fc2c333533f14c74edc0e85c49ffcd"),
            ModelFile("sherpa-onnx/decoder.int8.onnx", 1_049_105, "feb03e7c8aa761593a9284a6fa1e91259be3702de75f630062256c1a59c5a906"),
            ModelFile("sherpa-onnx/joiner.int8.onnx", 771_805, "1d2c6d8e6dabc5eb52de52e95718e9bdfbffbfe7ead3808fb5ffd74e83bd9cae"),
            ModelFile("sherpa-onnx/tokens.txt", 17_086, "e4ebb5e72a6aeef47437a55fa2308ebbedb7c51c557de7a89bf965c23ea9af4a"),
        ),
    )

    val all = listOf(ZIPFORMER_HE)
}
