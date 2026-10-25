package dev.sofatv.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.widget.Toast

/**
 * TV inputs (HDMI, tuner, AV) through the standard [TvInputManager], so no per-firmware code:
 *  - the system input picker is `com.android.tv.action.VIEW_INPUTS` (on TCL it opens the
 *    source menu of com.tcl.tv); without it we show our own list;
 *  - an input is opened with a passthrough channel URI ([TvContract.buildChannelUriForPassthroughInput]),
 *    which the TV app of the firmware handles; the tuner opens the TV app on its channels.
 *
 * Apps also register "inputs" for their preview channels (VLC, VK and others); only hardware
 * inputs are listed: passthrough ones and those of system packages.
 */
class TvInputs(private val context: Context) {

    class Input(val id: String, val label: String, val type: Int, val passthrough: Boolean) {
        val icon: Int
            get() = when (type) {
                TvInputInfo.TYPE_HDMI, TvInputInfo.TYPE_DVI, TvInputInfo.TYPE_DISPLAY_PORT -> R.drawable.ic_input_hdmi
                TvInputInfo.TYPE_TUNER -> R.drawable.ic_input_tv
                else -> R.drawable.ic_input
            }
    }

    private val manager: TvInputManager? = context.getSystemService(TvInputManager::class.java)

    /** Hardware inputs in the system order, without the ones hidden in the TV settings. */
    fun list(): List<Input> {
        val manager = manager ?: return emptyList()
        val pm = context.packageManager
        return try {
            manager.tvInputList.filter { info ->
                if (info.isHidden(context)) return@filter false
                if (info.isPassthroughInput) return@filter true
                val app = info.serviceInfo?.applicationInfo ?: return@filter false
                app.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(app.packageName) == null
            }.map { info ->
                val label = info.loadCustomLabel(context)?.toString()?.takeIf { it.isNotBlank() }
                    ?: info.loadLabel(context)?.toString().orEmpty()
                Input(info.id, label.ifBlank { info.id.substringAfterLast('/') }, info.type, info.isPassthroughInput)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The firmware's input picker; our own list where there is none. From the key service ([context]
     * isn't an Activity) that list can't be shown as a dialog, so it ends with a toast.
     */
    fun showPicker(activity: Context) {
        val picker = Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (picker.resolveActivity(context.packageManager) != null) {
            try {
                activity.startActivity(picker)
                return
            } catch (e: Exception) {
                // Fall through to our own list.
            }
        }
        val inputs = list()
        if (inputs.isEmpty() || activity !is Activity) {
            Toast.makeText(activity, if (inputs.isEmpty()) R.string.inputs_none else R.string.cannot_open, Toast.LENGTH_SHORT).show()
            return
        }
        TileMenu.show(
            activity,
            activity.getString(R.string.inputs),
            null,
            inputs.map { input -> TileMenu.Item(input.label, input.icon) { open(activity, input) } },
        )
    }

    fun open(activity: Context, input: Input) {
        val uri = if (input.passthrough) {
            TvContract.buildChannelUriForPassthroughInput(input.id)
        } else {
            TvContract.buildChannelsUriForInput(input.id)
        }
        val type = if (input.passthrough) TvContract.Channels.CONTENT_ITEM_TYPE else TvContract.Channels.CONTENT_TYPE
        // The data type is set explicitly: some TV apps only declare it, and resolving it through
        // the TV provider isn't guaranteed on every firmware.
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, uri),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, type),
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: ActivityNotFoundException) {
                // Try the next form.
            } catch (e: SecurityException) {
                // Try the next form.
            }
        }
        Toast.makeText(activity, R.string.cannot_open, Toast.LENGTH_SHORT).show()
    }

    /** By id, for the key action: the input may be gone since it was assigned. */
    fun open(context: Context, id: String) {
        val input = list().firstOrNull { it.id == id }
        if (input != null) open(context, input) else Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
    }
}
