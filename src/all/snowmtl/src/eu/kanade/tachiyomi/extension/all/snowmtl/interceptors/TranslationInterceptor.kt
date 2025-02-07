package eu.kanade.tachiyomi.extension.all.snowmtl.interceptors

import android.os.Build
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.extension.all.snowmtl.LanguageSetting
import eu.kanade.tachiyomi.extension.all.snowmtl.translator.TranslatorEngine
import eu.kanade.tachiyomi.multisrc.machinetranslations.Dialog
import eu.kanade.tachiyomi.multisrc.machinetranslations.MachineTranslations.Companion.PAGE_REGEX
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

@RequiresApi(Build.VERSION_CODES.O)
class TranslationInterceptor(
    var language: LanguageSetting,
    private val translator: TranslatorEngine,
) : Interceptor {

    private val json: Json by injectLazy()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (PAGE_REGEX.containsMatchIn(url).not() || language.target == language.origin) {
            return chain.proceed(request)
        }

        val dialogues = request.url.fragment?.parseAs<List<Dialog>>()
            ?: return chain.proceed(request)

        val translated = when {
            language.disableTranslationOptimization -> dialogues.map {
                it.replaceText(translator.translate(language.origin, language.target, it.text))
            }
            else -> translationOptimized(dialogues)
        }

        val newRequest = request.newBuilder()
            .url("${url.substringBeforeLast("#")}#${json.encodeToString(translated)}")
            .build()

        return chain.proceed(newRequest)
    }

    /**
     * Optimizes the translation of a list of dialogues.
     * This reduces the number of requests to the translator per page.
     *
     * @param dialogues List of Dialog objects to be translated.
     * @return List of translated Dialog objects.
     */
    private fun translationOptimized(dialogues: List<Dialog>): List<Dialog> {
        val mapping = buildMap(dialogues)

        val tokens = tokenizeAssociatedDialog(mapping).flatMap { token ->
            translator.translate(language.origin, language.target, token).split(delimiter)
        }

        return replaceDialoguesWithTranslations(tokens, mapping)
    }

    private fun replaceDialoguesWithTranslations(
        tokens: List<String>,
        mapping: Map<String, Pair<String, AssociatedDialog>>,
    ) = tokens.mapNotNull { token ->
        val list = try {
            token.decode().parseAs<List<String>>()
        } catch (_: Exception) {
            // The translator may return an invalid JSON, but it keeps the pattern sent.
            TRANSLATOR_EXTRACT_REGEX.findAll(token).map {
                listOf(
                    it.groups[1]?.value!!.encode(),
                    it.groups[3]?.value!!.let { dialog ->
                        dialog.takeIf { it.startsWith("\"") } ?: dialog.encode()
                    },
                )
            }.toList().flatten()
        }

        val key = list.first()
        val text = list.last()

        mapping[key]?.second?.dialog?.replaceText(text)
    }

    private fun Dialog.replaceText(value: String) = this.copy(
        textByLanguage = mutableMapOf(
            "text" to value,
        ),
    )

    /**
     * Tokenizes the associated dialogues.
     *
     * @param mapping Map of associated dialogues.
     * @return List of tokens.
     */
    private fun tokenizeAssociatedDialog(mapping: Map<String, Pair<String, AssociatedDialog>>) =
        tokenizeText(mapping.map { it.value.second.content })

    /**
     * Builds a map of dialogues associated with their identifiers.
     * I couldn't associate the translated dialog box with the zip method,
     * because some dialog boxes aren't associated correctly
     *
     * @param dialogues List of Dialog objects to be mapped.
     * @return Map where the key is the dialog identifier and the value is a pair containing the identifier and the associated dialog.
     */
    private fun buildMap(dialogues: List<Dialog>): Map<String, Pair<String, AssociatedDialog>> {
        return dialogues.map {
            val payload = json.encodeToString<List<String>>(listOf(it.hashCode().toString(), it.text))
                .encode()
            it.hashCode().toString() to AssociatedDialog(it, payload)
        }.associateBy { it.first }
    }

    // Prevents the translator's response from removing quotation marks from some texts
    private fun String.encode() = "\"${this}\""
    private fun String.decode() = this.substringAfter("\"").substringBeforeLast("\"")

    private val delimiter: String = "¦"

    /**
     * Tokenizes a list of texts based on the translator's character capacity per request
     *
     * @param texts List of texts to be tokenized.
     * @return List of tokens.
     */
    private fun tokenizeText(texts: List<String>): List<String> {
        val tokenized = mutableListOf<String>()

        val remainingText = buildString(translator.capacity) {
            texts.forEach { text ->
                if (length + text.length + delimiter.length > capacity()) {
                    tokenized += toString()
                    clear()
                }

                if (isNotEmpty()) {
                    append(delimiter)
                }

                append(text)
            }
        }

        if (remainingText.isNotEmpty()) {
            tokenized += remainingText
        }
        return tokenized
    }

    private inline fun <reified T> String.parseAs(): T {
        return json.decodeFromString(this)
    }

    companion object {
        val TRANSLATOR_EXTRACT_REGEX = """"?(-?\d+)(\\?")?,((\\?")?([^(\])]+))""".toRegex()
    }
}

private class AssociatedDialog(
    val dialog: Dialog,
    val content: String,
)
