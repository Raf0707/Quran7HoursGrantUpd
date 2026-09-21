package raf.console.quran7hours

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

private data class TafsirNote(val id: Int, val text: String)

private class TafsirFootnoteNavigator {
    val noteTargets = mutableMapOf<Int, BringIntoViewRequester>()
    val sourceTargets = mutableMapOf<String, BringIntoViewRequester>()
    val firstSourceForRef = mutableMapOf<Int, String>()
    val lastSourceForRef = mutableMapOf<Int, String>()

    fun registerSource(refs: List<Int>, sourceKey: String, requester: BringIntoViewRequester) {
        sourceTargets[sourceKey] = requester
        refs.forEach { firstSourceForRef.putIfAbsent(it, sourceKey) }
    }
    fun registerNote(ref: Int, requester: BringIntoViewRequester) { noteTargets[ref] = requester }
    suspend fun goToNote(ref: Int, sourceKey: String) {
        lastSourceForRef[ref] = sourceKey
        noteTargets[ref]?.bringIntoView()
    }
    suspend fun returnToSource(ref: Int) {
        val key = lastSourceForRef[ref] ?: firstSourceForRef[ref] ?: return
        sourceTargets[key]?.bringIntoView()
    }
}

@Composable
fun TafsirArticle(
    m: AdaptiveMetrics,
    blocks: List<JsonObject>?,
    fallback: String,
    settings: AppSettings,
    scopeKey: String = "article",
    title: String = "Комментарий к аяту"
) {
    val structured = !blocks.isNullOrEmpty()
    val resolved = if (structured) blocks.orEmpty() else fallback
        .replace("\r\n", "\n").replace('\r','\n')
        .split(Regex("\n\\s*\n+"))
        .map(String::trim).filter(String::isNotBlank)
        .map { JsonObject(mapOf("type" to JsonPrimitive("paragraph"), "text" to JsonPrimitive(it))) }
    if (resolved.isEmpty()) return

    val colors = LocalQuranColors.current
    val navigator = remember(scopeKey) { TafsirFootnoteNavigator() }
    val notes = remember(resolved) {
        val unique = linkedMapOf<Int, TafsirNote>()
        resolved.filter { it.qString("type") == "footnotes" }.forEach { block ->
            (block["items"] as? JsonArray)?.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val id = o.qInt("id")
                if (id > 0 && id !in unique) unique[id] = TafsirNote(id, o.qString("text"))
            }
        }
        unique.values.sortedBy { it.id }
    }
    val content = resolved.filter { it.qString("type") != "footnotes" }
    val words = resolved.sumOf { collectTafsirText(it).split(Regex("\\s+")).count(String::isNotBlank) }
    val minutes = (words / 175f).roundToInt().coerceAtLeast(1)
    val articleSize = m.body * settings.tafsirScale

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(m.xs)) {
        Text("Тафсир · Azan.ru", fontSize=m.bodySmall*.88f, color=colors.accent, fontWeight=FontWeight.Bold)
        Surface(
            shape=RoundedCornerShape(m.corner*.72f),
            color=mix(colors.surface2,colors.surface,.42f),
            border=BorderStroke(m.xs*.10f, mix(colors.line, Color.Transparent,.16f)),
            tonalElevation=m.xs*.06f
        ) {
            Column(Modifier.fillMaxWidth()) {
                Surface(color=mix(colors.accentSoft,colors.surface,.58f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=m.md,vertical=m.sm),
                        verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(m.sm)
                    ) {
                        Surface(shape=RoundedCornerShape(m.corner*.38f), color=mix(colors.accent,colors.surface,.88f), modifier=Modifier.size(m.touch*.62f)) {
                            Box(contentAlignment=Alignment.Center){ Icon(Icons.Default.MenuBook,null,Modifier.size(m.iconSmall*.78f),tint=colors.accent) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(m.xs*.35f)) {
                            Text(title,fontSize=articleSize*.92f,fontWeight=FontWeight.SemiBold,color=colors.text)
                            Text("$minutes мин чтения · ${content.size} смысловых блоков",fontSize=articleSize*.68f,color=colors.muted)
                        }
                    }
                }
                HorizontalDivider(color=mix(colors.line,Color.Transparent,.28f))
                Column(Modifier.fillMaxWidth().padding(m.md), verticalArrangement=Arrangement.spacedBy(m.md)) {
                    content.forEachIndexed { index, block ->
                        TafsirBlockOriginal(m,block,settings,navigator,"$scopeKey-b$index")
                    }
                }
                if(settings.showFootnotes && notes.isNotEmpty()) {
                    HorizontalDivider(color=mix(colors.line,Color.Transparent,.18f))
                    Surface(color=mix(colors.surface2,colors.surface,.24f)) {
                        Column(Modifier.fillMaxWidth().padding(m.md),verticalArrangement=Arrangement.spacedBy(m.sm)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(m.sm)){
                                Icon(Icons.Default.Notes,null,Modifier.size(m.iconSmall*.72f),tint=colors.muted)
                                Text("Источники и примечания",fontSize=articleSize*.76f,color=colors.muted,fontWeight=FontWeight.SemiBold)
                            }
                            notes.forEach { note -> FootnoteRow(m,note,articleSize,settings,navigator) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TafsirBlockOriginal(
    m: AdaptiveMetrics,
    block: JsonObject,
    settings: AppSettings,
    navigator: TafsirFootnoteNavigator,
    sourceKey: String
) {
    val colors=LocalQuranColors.current
    val size=m.body*settings.tafsirScale
    when(block.qString("type")){
        "heading" -> Text(
            block.qString("text"),fontSize=size*1.06f,lineHeight=size*1.42f,fontWeight=FontWeight.Bold,color=colors.text,
            textDecoration=TextDecoration.Underline
        )
        "paragraph" -> RichTafsirText(m,block.qString("text"),block.qRefs(),size,settings,navigator,"$sourceKey-p")
        "arabic" -> Text(block.qString("text"),fontFamily=QuranFont,fontSize=size*1.20f,lineHeight=size*2.0f,textAlign=TextAlign.End,color=colors.text,modifier=Modifier.fillMaxWidth())
        "list" -> TafsirListCard(m,block,settings,navigator,"$sourceKey-l")
        "quote" -> {
            val kind=block.qString("kind")
            val highlighted=kind=="ayah"||kind=="hadith"
            val nested=block["content"] as? JsonArray ?: JsonArray(emptyList())
            val nestedRefs=nested.flatMap{ (it as? JsonObject)?.let(::allRefsInContent).orEmpty() }.toSet()
            val trailing=block.qRefs().filterNot(nestedRefs::contains)
            if(highlighted){
                Surface(
                    shape=RoundedCornerShape(m.corner*.56f),
                    color=mix(colors.accentSoft,colors.surface,.42f),
                    border=BorderStroke(m.xs*.14f,mix(colors.accent,colors.line,.66f))
                ){
                    Row(Modifier.fillMaxWidth().padding(m.md),horizontalArrangement=Arrangement.spacedBy(m.sm),verticalAlignment=Alignment.Top){
                        Icon(Icons.Default.FormatQuote,null,Modifier.size(m.iconSmall*.72f),tint=colors.accent)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(m.sm)){
                            nested.forEachIndexed{i,el->(el as? JsonObject)?.let{TafsirQuoteContent(m,it,settings,navigator,"$sourceKey-q$i",italic=true,highlighted=true)}}
                            if(trailing.isNotEmpty()) RichTafsirText(m,"",trailing,size,settings,navigator,"$sourceKey-tail")
                        }
                    }
                }
            }else{
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(m.sm)){
                    nested.forEachIndexed{i,el->(el as? JsonObject)?.let{TafsirQuoteContent(m,it,settings,navigator,"$sourceKey-q$i",italic=false,highlighted=false)}}
                    if(trailing.isNotEmpty()) RichTafsirText(m,"",trailing,size,settings,navigator,"$sourceKey-tail")
                }
            }
        }
    }
}

@Composable
private fun TafsirQuoteContent(
    m:AdaptiveMetrics,
    block:JsonObject,
    settings:AppSettings,
    navigator:TafsirFootnoteNavigator,
    sourceKey:String,
    italic:Boolean,
    highlighted:Boolean
){
    val colors=LocalQuranColors.current
    val size=m.body*settings.tafsirScale
    when(block.qString("type")){
        "arabic"-> if(highlighted) {
            Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(m.xs)) {
                Text(block.qString("text"),fontFamily=QuranFont,fontSize=size*1.42f,lineHeight=size*1.95f,fontWeight=FontWeight.Medium,textAlign=TextAlign.End,color=colors.text,modifier=Modifier.fillMaxWidth())
                HorizontalDivider(color=mix(colors.line,Color.Transparent,.38f))
            }
        } else Text(block.qString("text"),fontFamily=QuranFont,fontSize=size*1.20f,lineHeight=size*1.90f,textAlign=TextAlign.End,color=colors.text,modifier=Modifier.fillMaxWidth())
        "paragraph"-> RichTafsirText(m,block.qString("text"),block.qRefs(),size,settings,navigator,sourceKey,italic=italic)
        "list"-> TafsirListCard(m,block,settings,navigator,sourceKey)
    }
}

@Composable
private fun TafsirListCard(m:AdaptiveMetrics,block:JsonObject,settings:AppSettings,navigator:TafsirFootnoteNavigator,sourceKey:String){
    val colors=LocalQuranColors.current
    val size=m.body*settings.tafsirScale
    val items=block["items"] as? JsonArray ?: JsonArray(emptyList())
    Surface(shape=RoundedCornerShape(m.corner*.48f),color=mix(colors.surface2,colors.surface,.34f),border=BorderStroke(m.xs*.10f,mix(colors.accent,colors.line,.55f))){
        Column(Modifier.fillMaxWidth().padding(m.sm),verticalArrangement=Arrangement.spacedBy(m.sm)){
            items.forEachIndexed{index,el->
                val o=el as? JsonObject
                val text=o?.qString("text") ?: (el as? JsonPrimitive)?.contentOrNull.orEmpty()
                val refs=o?.qRefs().orEmpty()
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(m.sm),verticalAlignment=Alignment.Top){
                    val numbered=block.qString("style")=="numbered"
                    Surface(shape=RoundedCornerShape(m.corner*.28f),color=if(numbered)colors.accentSoft else Color.Transparent,modifier=Modifier.size(if(numbered)m.touch*.48f else m.touch*.34f)){
                        Box(contentAlignment=Alignment.Center){Text(if(numbered)"${index+1}" else "•",fontSize=if(numbered)size*.72f else size*1.08f,color=colors.accent,fontWeight=FontWeight.Bold)}
                    }
                    RichTafsirText(m,text,refs,size,settings,navigator,"$sourceKey-i$index",modifier=Modifier.weight(1f))
                }
            }
            if(block.qRefs().isNotEmpty()) RichTafsirText(m,"",block.qRefs(),size,settings,navigator,"$sourceKey-block")
        }
    }
}

@Composable
private fun RichTafsirText(
    m:AdaptiveMetrics,
    text:String,
    refs:List<Int>,
    size:TextUnit,
    settings:AppSettings,
    navigator:TafsirFootnoteNavigator,
    sourceKey:String,
    modifier:Modifier=Modifier,
    italic:Boolean=false
){
    val colors=LocalQuranColors.current
    val scope=rememberCoroutineScope()
    val requester=remember(sourceKey){BringIntoViewRequester()}
    SideEffect { if(settings.showFootnotes && refs.isNotEmpty()) navigator.registerSource(refs,sourceKey,requester) }
    val annotated=remember(text,refs,settings.showFootnotes,italic,colors.text,colors.muted){
        buildAnnotatedString{
            val regex=Regex("(\\([^()]{2,180}\\))")
            var cursor=0
            regex.findAll(text).forEach{match->
                if(match.range.first>cursor) append(text.substring(cursor,match.range.first))
                pushStyle(SpanStyle(fontStyle=FontStyle.Italic,color=mix(colors.text,colors.muted,.22f)))
                append(match.value)
                pop();cursor=match.range.last+1
            }
            if(cursor<text.length)append(text.substring(cursor))
            if(italic && length>0)addStyle(SpanStyle(fontStyle=FontStyle.Italic,fontWeight=FontWeight.Medium),0,length)
            if(settings.showFootnotes && refs.isNotEmpty()){
                if(length>0)append(" ")
                refs.forEachIndexed{i,ref->
                    if(i>0)append(" ")
                    appendInlineContent("$sourceKey-ref-$ref-$i","[$ref]")
                }
            }
        }
    }
    val inline=if(!settings.showFootnotes) emptyMap() else refs.mapIndexed{i,ref->
        val id="$sourceKey-ref-$ref-$i"
        id to InlineTextContent(
            Placeholder(
                width = size * 1.22f,
                height = size * 1.22f,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
            )
        ) {
            Surface(
                shape = CircleShape, color = mix(colors.accentSoft, colors.surface, .28f),
                border = BorderStroke(m.xs * .10f, mix(colors.accent, colors.line, .48f)),
                onClick = { scope.launch { navigator.goToNote(ref, sourceKey) } },
                modifier = Modifier.fillMaxSize().semantics { contentDescription = "Источник $ref" }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        ref.toString(),
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        style = TextStyle(
                            fontSize = size * .58f,
                            lineHeight = size * .58f,
                            platformStyle = PlatformTextStyle(includeFontPadding = false)
                        )
                    )
                }
            }
        }
    }.toMap()
    Text(
        text=annotated, inlineContent=inline, modifier=modifier.bringIntoViewRequester(requester),
        fontSize=size,lineHeight=size*1.72f,color=colors.text
    )
}

@Composable
private fun FootnoteRow(m:AdaptiveMetrics,note:TafsirNote,size:TextUnit,settings:AppSettings,navigator:TafsirFootnoteNavigator){
    val colors=LocalQuranColors.current
    val scope=rememberCoroutineScope()
    val requester=remember(note.id){BringIntoViewRequester()}
    SideEffect{navigator.registerNote(note.id,requester)}
    Surface(
        onClick={scope.launch{navigator.returnToSource(note.id)}},
        shape=RoundedCornerShape(m.corner*.42f),
        color=mix(colors.surface,colors.surface2,.30f),
        border=BorderStroke(m.xs*.08f,Color.Transparent),
        modifier=Modifier.fillMaxWidth().bringIntoViewRequester(requester)
    ){
        Row(Modifier.fillMaxWidth().padding(m.sm),horizontalArrangement=Arrangement.spacedBy(m.sm),verticalAlignment=Alignment.CenterVertically){
            Surface(shape=CircleShape,color=mix(colors.accentSoft,colors.surface,.28f),border=BorderStroke(m.xs*.10f,mix(colors.accent,colors.line,.48f)),modifier=Modifier.size(m.touch*.48f)){
                Box(contentAlignment=Alignment.Center){
                    Text(
                        note.id.toString(),
                        color=colors.accent,
                        fontWeight=FontWeight.Bold,
                        textAlign=TextAlign.Center,
                        style=TextStyle(fontSize=size*.58f,lineHeight=size*.58f,platformStyle=PlatformTextStyle(includeFontPadding=false))
                    )
                }
            }
            Text(note.text,fontSize=size*settings.footnoteScale*.78f,lineHeight=size*settings.footnoteScale*1.20f,color=colors.muted,modifier=Modifier.weight(1f))
        }
    }
}

private fun collectTafsirText(o:JsonObject):String=when(o.qString("type")){
    "paragraph","heading","arabic"->o.qString("text")
    "quote"->(o["content"] as? JsonArray).orEmpty().joinToString(" "){(it as? JsonObject)?.let(::collectTafsirText).orEmpty()}
    "list"->(o["items"] as? JsonArray).orEmpty().joinToString(" "){(it as? JsonObject)?.qString("text") ?: (it as? JsonPrimitive)?.contentOrNull.orEmpty()}
    "footnotes"->(o["items"] as? JsonArray).orEmpty().joinToString(" "){(it as? JsonObject)?.qString("text").orEmpty()}
    else->""
}

private fun allRefsInContent(o:JsonObject):List<Int>{
    val refs=o.qRefs().toMutableList()
    if(o.qString("type")=="list") (o["items"] as? JsonArray)?.forEach{(it as? JsonObject)?.qRefs()?.let(refs::addAll)}
    return refs.distinct()
}
private fun JsonObject.qString(key:String):String=(this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.qInt(key:String):Int=(this[key] as? JsonPrimitive)?.intOrNull?:0
private fun JsonObject.qRefs(): List<Int> = (this["refs"] as? JsonArray)?.mapNotNull{(it as? JsonPrimitive)?.intOrNull}.orEmpty()
