package ai.meteor.kcode.webcontainer

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

object WebDebugScript {
    val consoleCapture: String = """
        (function(){
          if(window.__kcodeDebugConsoleInstalled)return true;
          Object.defineProperty(window,'__kcodeDebugConsoleInstalled',{value:true});
          var sequence=0,entries=[];window.__kcodeDebugConsole=entries;
          function text(value){try{return typeof value==='string'?value:JSON.stringify(value);}catch(_){return String(value);}}
          function record(level,args,source,line){entries.push({sequence:++sequence,level:level,message:Array.from(args).map(text).join(' '),source:source||null,line:line||null});if(entries.length>500)entries.shift();}
          ['log','info','warn','error','debug'].forEach(function(level){var original=console[level];console[level]=function(){record(level,arguments);return original.apply(console,arguments);};});
          addEventListener('error',function(e){record('error',[e.message],e.filename,e.lineno);});
          addEventListener('unhandledrejection',function(e){record('error',['Unhandled promise rejection',e.reason]);});
          return true;
        })()
    """.trimIndent()

    val inspect: String = """
        (function(){
          var query='a[href],button,input,textarea,select,summary,[role="button"],[role="link"],[role="checkbox"],[role="radio"],[role="tab"],[contenteditable="true"],[tabindex]';
          var sequence=window.__kcodeDebugHandleSequence||0;
          var elements=Array.from(document.querySelectorAll(query)).filter(function(el){
            var r=el.getBoundingClientRect(),s=getComputedStyle(el);
            return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';
          }).slice(0,200).map(function(el){
            var handle=el.getAttribute('data-kcode-handle');
            if(!handle){handle='element-'+(++sequence);el.setAttribute('data-kcode-handle',handle);}
            var r=el.getBoundingClientRect();
            var name=el.getAttribute('aria-label')||el.getAttribute('title')||el.innerText||el.value||el.getAttribute('alt')||el.getAttribute('name')||'';
            return {handle:handle,tag:el.tagName.toLowerCase(),role:el.getAttribute('role'),name:String(name).trim().slice(0,500),
              selector:'[data-kcode-handle="'+handle+'"]',x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height),
              disabled:!!(el.disabled||el.getAttribute('aria-disabled')==='true')};
          });
          window.__kcodeDebugHandleSequence=sequence;
          return JSON.stringify({url:location.href,title:document.title||'',viewportWidth:innerWidth,viewportHeight:innerHeight,elements:elements});
        })()
    """.trimIndent()

    fun interact(request: WebInteractionRequest): String {
        val payload = buildJsonObject {
            put("action", request.action.code)
            request.handle?.let { put("handle", it) }
            request.selector?.let { put("selector", it) }
            request.x?.let { put("x", it) }
            request.y?.let { put("y", it) }
            request.text?.let { put("text", it) }
            put("deltaX", request.deltaX)
            put("deltaY", request.deltaY)
            request.key?.let { put("key", it) }
        }
        return """
            (function(p){
              function target(){
                if(p.handle)return document.querySelector('[data-kcode-handle="'+CSS.escape(p.handle)+'"]');
                if(p.selector)return document.querySelector(p.selector);
                if(p.x!=null&&p.y!=null)return document.elementFromPoint(p.x,p.y);
                return document.activeElement&&document.activeElement!==document.body?document.activeElement:null;
              }
              var el,t='page';
              if(p.action==='click'){
                el=target();if(!el)throw new Error('Interaction target not found');
                el.scrollIntoView({block:'nearest',inline:'nearest'});el.focus({preventScroll:true});el.click();t=p.handle||p.selector||('point('+p.x+','+p.y+')');
              }else if(p.action==='input'){
                el=target();if(!el)throw new Error('Input target not found');
                var value=p.text||'';
                if(el instanceof HTMLSelectElement){el.value=value;
                }else if(el.isContentEditable){el.textContent=value;
                }else if(el instanceof HTMLTextAreaElement||el instanceof HTMLInputElement){
                  var proto=el instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                  var setter=Object.getOwnPropertyDescriptor(proto,'value');
                  if(setter&&setter.set)setter.set.call(el,value);else el.value=value;
                }else throw new Error('Target does not accept text input');
                el.focus();el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:value}));
                el.dispatchEvent(new Event('change',{bubbles:true}));t=p.handle||p.selector||el.tagName.toLowerCase();
              }else if(p.action==='scroll'){
                window.scrollBy({left:p.deltaX||0,top:p.deltaY||0,behavior:'instant'});t='viewport';
              }else if(p.action==='key'){
                el=target()||document.body;var key=p.key||'';if(!key)throw new Error('key is required');
                ['keydown','keypress','keyup'].forEach(function(type){el.dispatchEvent(new KeyboardEvent(type,{key:key,code:key,bubbles:true,cancelable:true}));});t=p.handle||p.selector||'active-element';
              }else if(p.action==='reload'){setTimeout(function(){location.reload();},0);t='page';
              }else if(p.action==='back'){setTimeout(function(){history.back();},0);t='history';
              }else throw new Error('Unsupported interaction action: '+p.action);
              return JSON.stringify({target:t});
            })($payload)
        """.trimIndent()
    }

    fun console(cursor: Long, limit: Int): String =
        "JSON.stringify((window.__kcodeDebugConsole||[]).filter(function(e){return e.sequence>${cursor.coerceAtLeast(0)}}).slice(0,${limit.coerceIn(1, 200)}))"
}

fun decodeWebInspection(containerId: String, encoded: String): WebPageInspection {
    val root = Json.parseToJsonElement(encoded).jsonObject
    return WebPageInspection(
        containerId = containerId,
        url = root.getValue("url").jsonPrimitive.content,
        title = root.getValue("title").jsonPrimitive.content,
        viewportWidth = root.getValue("viewportWidth").jsonPrimitive.int,
        viewportHeight = root.getValue("viewportHeight").jsonPrimitive.int,
        elements = root.getValue("elements").jsonArray.map { item ->
            val element = item.jsonObject
            WebInteractiveElement(
                handle = element.getValue("handle").jsonPrimitive.content,
                tag = element.getValue("tag").jsonPrimitive.content,
                role = element["role"]?.jsonPrimitive?.contentOrNull,
                name = element.getValue("name").jsonPrimitive.content,
                selector = element.getValue("selector").jsonPrimitive.content,
                x = element.getValue("x").jsonPrimitive.int,
                y = element.getValue("y").jsonPrimitive.int,
                width = element.getValue("width").jsonPrimitive.int,
                height = element.getValue("height").jsonPrimitive.int,
                disabled = element.getValue("disabled").jsonPrimitive.boolean,
            )
        },
    )
}

fun decodeWebInteractionTarget(encoded: String): String =
    Json.parseToJsonElement(encoded).jsonObject.getValue("target").jsonPrimitive.content

fun decodeWebConsole(containerId: String, encoded: String, cursor: Long): WebConsoleSnapshot {
    val entries = Json.parseToJsonElement(encoded).jsonArray.map { item ->
        val entry = item.jsonObject
        WebConsoleEntry(
            sequence = entry.getValue("sequence").jsonPrimitive.content.toLong(),
            level = entry.getValue("level").jsonPrimitive.content,
            message = entry.getValue("message").jsonPrimitive.content,
            source = entry["source"]?.jsonPrimitive?.contentOrNull,
            line = entry["line"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }
    return WebConsoleSnapshot(containerId, entries, entries.lastOrNull()?.sequence ?: cursor)
}
