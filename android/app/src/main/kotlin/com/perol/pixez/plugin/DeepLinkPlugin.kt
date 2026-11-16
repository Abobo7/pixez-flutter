package com.perol.pixez.plugin

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry.NewIntentListener

class DeepLinkPlugin : FlutterPlugin,
    MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    ActivityAware,
    NewIntentListener {
    private var context: Context? = null
    private var activity: Activity? = null
    private var eventSink: EventSink? = null

    private var initialLink: String? = null
    private var latestLink: String? = null
    private var initialIntent = true
    private var lastHandledIntentId: Int = 0

    companion object {
        private const val MESSAGES_CHANNEL = "deep_links/messages"
        private const val EVENTS_CHANNEL = "deep_links/events"
    }

    private fun extractLinkFromIntent(intent: Intent): String? {
        if (intent.extras != null && intent.hasExtra("iid")) {
            val iid = intent.getLongExtra("iid", 0)
            if (iid != 0L) {
                return "pixez://www.pixiv.net/artworks/$iid"
            }
        }
        if (Intent.ACTION_VIEW != intent.action) {
            return null
        }
        val raw = intent.dataString ?: return null
        if (raw.startsWith("intent://")) {
            try {
                val parsed = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
                if (!parsed.dataString.isNullOrEmpty()) {
                    return parsed.dataString
                }
            } catch (_: Throwable) {
            }
        }
        return raw
    }

    private fun handleIntent(intent: Intent) {
        val intentId = System.identityHashCode(intent)
        if (intentId != 0 && intentId == lastHandledIntentId) {
            return
        }
        val link = extractLinkFromIntent(intent) ?: return
        lastHandledIntentId = intentId
        if (initialIntent) {
            initialLink = link
            initialIntent = false
        }
        val sink = eventSink
        if (sink != null) {
            latestLink = null
            sink.success(link)
        } else {
            latestLink = link
        }
    }

    private fun register(messenger: BinaryMessenger, plugin: DeepLinkPlugin) {
        val methodChannel = MethodChannel(messenger, MESSAGES_CHANNEL)
        methodChannel.setMethodCallHandler(plugin)
        val eventChannel = EventChannel(messenger, EVENTS_CHANNEL)
        eventChannel.setStreamHandler(plugin)
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.context = binding.applicationContext
        register(binding.binaryMessenger, this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        eventSink = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInitialLink" -> {
                val link = initialLink
                initialLink = null
                result.success(link)
            }

            "getLatestLink" -> {
                val link = latestLink
                latestLink = null
                result.success(link)
            }

            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventSink?) {
        eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
        handleIntent(binding.activity.intent)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addOnNewIntentListener(this)
        handleIntent(binding.activity.intent)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onNewIntent(intent: Intent): Boolean {
        activity?.intent = intent
        handleIntent(intent)
        return false
    }
}
