package org.autojs.autojs.ui.main.drawer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.runtime.api.ProcessShell
import org.autojs.autojs.runtime.api.WrappedShizuku
import org.autojs.autojs.ui.main.drawer.IPermissionItem.Companion.ACTION
import org.autojs.autojs.util.ClipboardUtils
import org.autojs.autojs.util.IntentUtils
import org.autojs.autojs.util.RootUtils
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R
import java.util.concurrent.CountDownLatch

interface CommandBasedPermissionItemHelper : PermissionItemHelper, IPermissionRootItem, IPermissionShizukuItem, IPermissionAdbItem {

    override fun request(): Boolean {
        if (RootUtils.isRootAvailable() && requestWithRoot()) {
            return true
        }
        if (WrappedShizuku.hasPermission() && WrappedShizuku.isRunning() && requestWithShizuku()) {
            return true
        }
        return requestWithAdb()
    }

    override fun revoke(): Boolean {
        if (RootUtils.isRootAvailable() && revokeWithRoot()) {
            return true
        }
        if (WrappedShizuku.hasPermission() && WrappedShizuku.isRunning() && revokeWithShizuku()) {
            return true
        }
        return revokeWithAdb()
    }

    override fun requestWithRoot() = withRoot(
        ACTION.REQUEST,
        R.string.text_permission_granted_with_root,
        R.string.text_permission_granted_failed_with_root,
    )

    override fun revokeWithRoot() = withRoot(
        ACTION.REVOKE,
        R.string.text_permission_revoked_with_root,
        R.string.text_permission_revoked_failed_with_root,
    )

    override fun requestWithShizuku() = withShizuku(
        ACTION.REQUEST,
        R.string.text_permission_granted_with_shizuku,
        R.string.text_permission_granted_failed_with_shizuku,
    )

    override fun revokeWithShizuku() = withShizuku(
        ACTION.REVOKE,
        R.string.text_permission_revoked_with_shizuku,
        R.string.text_permission_revoked_failed_with_shizuku,
    )

    override fun requestWithAdb() = withAdb(ACTION.REQUEST)

    override fun revokeWithAdb() = withAdb(ACTION.REVOKE)

    fun isActionMatched(action: ACTION) = when (has()) {
        true -> action == ACTION.REQUEST
        else -> action == ACTION.REVOKE
    }

    fun withRoot(action: ACTION, onSuccessMessageRes: Int, onFailureMessageRes: Int): Boolean {
        try {
            ProcessShell.execCommand(getCommand(action), true)
            if (isActionMatched(action)) {
                return true.also { ViewUtils.showToast(context, onSuccessMessageRes) }
            }
        } catch (ignore: Exception) {
            /* Ignored. */
        }
        return false.also { ViewUtils.showToast(context, onFailureMessageRes, true) }
    }

    fun withShizuku(action: ACTION, onSuccessMessageRes: Int, onFailureMessageRes: Int): Boolean {
        try {
            WrappedShizuku.execCommand(context, getCommand(action))
            if (isActionMatched(action)) {
                return true.also { ViewUtils.showToast(context, onSuccessMessageRes) }
            }
        } catch (ignore: Exception) {
            /* Ignored. */
        }
        return false.also { ViewUtils.showToast(context, onFailureMessageRes, true) }
    }

    fun withAdb(action: ACTION): Boolean {
        val dialogDismissSignal = CountDownLatch(1)

        AdbDialogBuilder(context, getCommand(action))
            .setChecker(object : AdbDialogBuilder.Checker {
                override fun check() = has()
            })
            .build()
            .dismissListener { dialogDismissSignal.countDown() }
            .let { Handler(Looper.getMainLooper()).post { it.show() } }

        try {
            dialogDismissSignal.await()
        } catch (_: InterruptedException) {
            /* Ignored. */
        }

        return has()
    }

    private class AdbDialogBuilder(private val context: Context, command: String) {

        interface Checker {
            fun check(): Boolean
        }

        private val mRawShellCommand = command
        private val mCommand = "adb shell $command"
        private var mSnackBarDuration = 1000
        private var mChecker: Checker? = null

        fun setSnackBarDuration(duration: Int) = also { mSnackBarDuration = duration }

        fun setChecker(checker: Checker?) = also { mChecker = checker }

        fun build() = mChecker?.let {
            MaterialDialog.Builder(context)
                .title(R.string.text_adb_tool_needed)
                .content(mCommand)
                .neutralText(R.string.text_copy_command)
                .neutralColorRes(R.color.dialog_button_hint)
                .onNeutral { dialog, _ ->
                    ClipboardUtils.setClip(context, mCommand)
                    val view = dialog.view
                    val resultRes = R.string.text_command_already_copied_to_clip
                    if (view != null) {
                        ViewUtils.showSnack(view, resultRes, mSnackBarDuration)
                    } else {
                        ViewUtils.showToast(context, resultRes)
                    }
                }
                .negativeText(R.string.dialog_button_cancel)
                .negativeColorRes(R.color.dialog_button_default)
                .onNegative { dialog, _ -> dialog.dismiss() }
                .positiveText(R.string.text_pair_code)
                .positiveColorRes(R.color.dialog_button_hint)
                .onPositive { dialog, _ ->
                    dialog.dismiss()
                    showPairEndpointDialog(it)
                }
                .autoDismiss(false)
        } ?: throw Exception("A checker is required for AdbDialogBuilder")

        private fun showPairEndpointDialog(checker: Checker) {
            MaterialDialog.Builder(context)
                .title(R.string.text_adb_pair_endpoint)
                .content(R.string.text_adb_pair_endpoint_hint)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .input(
                    context.getString(R.string.text_adb_pair_endpoint_placeholder),
                    context.getString(R.string.text_adb_pair_endpoint_default),
                ) { dialog, input ->
                    val pairEndpoint = input?.toString()?.trim().orEmpty()
                    if (!isValidHostPort(pairEndpoint)) {
                        ViewUtils.showSnack(dialog.view, R.string.error_invalid_host_port, true)
                        return@input
                    }
                    dialog.dismiss()
                    showPairCodeDialog(checker, pairEndpoint)
                }
                .neutralText(R.string.text_permission_test)
                .neutralColorRes(R.color.dialog_button_hint)
                .onNeutral { _, _ ->
                    IntentUtils.launchDeveloperOptionsOrSettings(context)
                }
                .positiveText(R.string.dialog_button_next_step)
                .negativeText(R.string.dialog_button_cancel)
                .autoDismiss(false)
                .show()
        }

        private fun showPairCodeDialog(checker: Checker, pairEndpoint: String) {
            MaterialDialog.Builder(context)
                .title(R.string.text_pair_code)
                .content(R.string.text_adb_pair_code_hint)
                .inputType(InputType.TYPE_CLASS_NUMBER)
                .input(
                    context.getString(R.string.text_pair_code),
                    "",
                ) { dialog, input ->
                    val pairCode = input?.toString()?.trim().orEmpty()
                    if (pairCode.length < 6) {
                        ViewUtils.showSnack(dialog.view, R.string.error_invalid_pair_code, true)
                        return@input
                    }
                    dialog.dismiss()
                    showDebugEndpointDialog(checker, pairEndpoint, pairCode)
                }
                .positiveText(R.string.dialog_button_next_step)
                .negativeText(R.string.dialog_button_cancel)
                .autoDismiss(false)
                .show()
        }

        private fun showDebugEndpointDialog(checker: Checker, pairEndpoint: String, pairCode: String) {
            val host = pairEndpoint.substringBefore(':')
            val defaultDebugEndpoint = "$host:5555"
            MaterialDialog.Builder(context)
                .title(R.string.text_adb_debug_endpoint)
                .content(R.string.text_adb_debug_endpoint_hint)
                .inputType(InputType.TYPE_CLASS_TEXT)
                .input(
                    context.getString(R.string.text_adb_debug_endpoint_placeholder),
                    defaultDebugEndpoint,
                ) { dialog, input ->
                    val debugEndpoint = input?.toString()?.trim().orEmpty()
                    if (!isValidHostPort(debugEndpoint)) {
                        ViewUtils.showSnack(dialog.view, R.string.error_invalid_host_port, true)
                        return@input
                    }
                    dialog.dismiss()
                    executeWirelessFlow(checker, pairEndpoint, pairCode, debugEndpoint)
                }
                .positiveText(R.string.dialog_button_connect)
                .negativeText(R.string.dialog_button_cancel)
                .autoDismiss(false)
                .show()
        }

        private fun executeWirelessFlow(checker: Checker, pairEndpoint: String, pairCode: String, debugEndpoint: String) {
            ViewUtils.showToast(context, R.string.text_adb_wireless_connecting)

            Thread {
                val probeResult = ProcessShell.execCommand("adb version", false)
                if (probeResult.code != 0) {
                    Handler(Looper.getMainLooper()).post {
                        ViewUtils.showToast(context, R.string.error_adb_client_unavailable, true)
                    }
                    return@Thread
                }

                val pairResult = ProcessShell.execCommand("adb pair $pairEndpoint $pairCode", false)
                if (pairResult.code != 0) {
                    Handler(Looper.getMainLooper()).post {
                        ViewUtils.showToast(context, R.string.error_adb_pair_failed, true)
                    }
                    return@Thread
                }

                val connectResult = ProcessShell.execCommand("adb connect $debugEndpoint", false)
                if (connectResult.code != 0) {
                    Handler(Looper.getMainLooper()).post {
                        ViewUtils.showToast(context, R.string.error_adb_connect_failed, true)
                    }
                    return@Thread
                }

                ProcessShell.execCommand("adb shell $mRawShellCommand", false)

                Handler(Looper.getMainLooper()).post {
                    val resultRes = if (checker.check()) R.string.text_granted else R.string.text_not_granted
                    ViewUtils.showToast(context, resultRes)
                }
            }.start()
        }

        private fun isValidHostPort(value: String): Boolean {
            val idx = value.lastIndexOf(':')
            if (idx <= 0 || idx >= value.lastIndex) return false
            val host = value.substring(0, idx)
            val port = value.substring(idx + 1).toIntOrNull() ?: return false
            return host.isNotBlank() && port in 1..65535
        }

    }

}
