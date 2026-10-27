package com.slte.app.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.slte.app.R
import com.slte.app.domain.model.PasswordChangeOutcome
import com.slte.app.domain.repository.AuthRepository
import com.slte.app.domain.repository.LocaleRepository
import com.slte.app.domain.repository.SubscribeRepository
import com.slte.app.domain.repository.ThemeRepository
import com.slte.app.kernel.KernelProxy
import com.slte.app.kernel.api.KernelType
import com.slte.app.kernel.api.KernelVpnState
import com.slte.app.kernel.factory.KernelFactory
import com.slte.app.kernel.factory.ProcessRestarter
import com.slte.app.ui.component.SubmitTip
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class RemindSync { Loading, Idle, Saving }

data class SettingsData(
    val expireRemindEnabled: Boolean = true,
    val trafficRemindEnabled: Boolean = true,
    val remindSync: RemindSync = RemindSync.Loading,
    val errorMessageRes: Int? = null,
    val tunStackMode: TunStackMode = TunStackMode.DEFAULT,

    val tunStackSwitchCount: Int = 0,

    val darkModeEnabled: Boolean = false,

    val locale: Locale? = null,

    val availableKernels: List<KernelType> = emptyList(),
    val currentKernel: KernelType? = null,
    val kernelSwitching: Boolean = false,
)

data class ChangePasswordForm(
    val oldPassword: String = "",
    val newPassword: String = "",
    val confirmPassword: String = "",
)

sealed interface ChangePasswordState {
    object Closed : ChangePasswordState

    data class Editing(
        val form: ChangePasswordForm = ChangePasswordForm(),
        val submitting: Boolean = false,
    ) : ChangePasswordState
}

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val authRepository: AuthRepository,
    private val subscribeRepository: SubscribeRepository,
    private val kernelProxy: KernelProxy,
    private val themeRepository: ThemeRepository,
    private val localeRepository: LocaleRepository,
    private val kernelFactory: KernelFactory,
    private val processRestarter: ProcessRestarter,
) : ViewModel() {
    private val _data =
        MutableStateFlow(
            SettingsData(
                darkModeEnabled = themeRepository.dark.value,
                locale = localeRepository.locale.value,
            ),
        )
    val data: StateFlow<SettingsData> = _data.asStateFlow()

    private val _changePasswordState = MutableStateFlow<ChangePasswordState>(ChangePasswordState.Closed)
    val changePasswordState: StateFlow<ChangePasswordState> = _changePasswordState.asStateFlow()

    private val _tip = MutableStateFlow<SubmitTip?>(null)
    val tip: StateFlow<SubmitTip?> = _tip.asStateFlow()

    init {
        loadRemindSettings()
        loadTunStackMode()
        loadKernelInfo()
    }

    fun setDarkMode(enabled: Boolean) {
        themeRepository.setDark(enabled)
        _data.value = _data.value.copy(darkModeEnabled = enabled)
    }

    fun setLocale(locale: Locale?) {
        localeRepository.setLocale(locale)
        _data.value = _data.value.copy(locale = locale)
    }

    private fun loadTunStackMode() {
        viewModelScope.launch {
            val mode = TunStackMode.fromValue(kernelProxy.tunStackMode())
            _data.value = _data.value.copy(tunStackMode = mode)
        }
    }

    fun setTunStackMode(mode: TunStackMode) {
        if (_data.value.tunStackMode == mode) return
        viewModelScope.launch {
            kernelProxy.setTunStack(mode.value)
            _data.value =
                _data.value.copy(
                    tunStackMode = mode,
                    tunStackSwitchCount = _data.value.tunStackSwitchCount + 1,
                )
        }
    }

    fun consumeTunStackSwitch() {
        _data.value = _data.value.copy(tunStackSwitchCount = 0)
    }

    private fun loadKernelInfo() {
        _data.value =
            _data.value.copy(
                availableKernels = kernelFactory.availableKernels(),
                currentKernel = kernelFactory.current().type,
            )
    }

    /** 停 VPN（若在跑）→ 持久化 → 重启进程。 */
    fun switchKernel(type: KernelType) {
        val data = _data.value
        if (type == data.currentKernel || data.kernelSwitching) return
        if (type !in data.availableKernels) return
        _data.value = data.copy(kernelSwitching = true)
        viewModelScope.launch {
            val kernel = kernelFactory.current()
            if (kernel.vpnStatus.value.state != KernelVpnState.STOPPED) {
                kernel.stopVpn()
                val stopped =
                    withTimeoutOrNull(SWITCH_STOP_TIMEOUT_MS) {
                        kernel.vpnStatus.first { it.state == KernelVpnState.STOPPED || it.state == KernelVpnState.FAILED }
                    } != null
                if (!stopped) {
                    _data.value = _data.value.copy(kernelSwitching = false)
                    _tip.value = SubmitTip(messageRes = R.string.settings_kernel_switch_failed)
                    return@launch
                }
            }
            runCatching { kernelFactory.switchTo(type) }
                .onSuccess {
                    _data.value = _data.value.copy(currentKernel = type)
                    processRestarter.restart()
                }.onFailure {
                    _data.value = _data.value.copy(kernelSwitching = false)
                    _tip.value = SubmitTip(messageRes = R.string.settings_kernel_switch_failed)
                }
        }
    }

    fun loadRemindSettings() {
        viewModelScope.launch {
            subscribeRepository
                .fetchUserInfo(force = true)
                .onSuccess { user ->
                    _data.value =
                        _data.value.copy(
                            expireRemindEnabled = user.remindExpire == 1,
                            trafficRemindEnabled = user.remindTraffic == 1,
                            remindSync = RemindSync.Idle,
                            errorMessageRes = null,
                        )
                }.onFailure {
                    _data.value = _data.value.copy(remindSync = RemindSync.Idle)
                }
        }
    }

    fun setExpireRemind(enabled: Boolean) {
        if (_data.value.remindSync != RemindSync.Idle) return
        _data.value = _data.value.copy(expireRemindEnabled = enabled, remindSync = RemindSync.Saving, errorMessageRes = null)
        viewModelScope.launch {
            authRepository
                .updateRemindExpire(enabled)
                .onSuccess {
                    _data.value = _data.value.copy(remindSync = RemindSync.Idle)
                }.onFailure {
                    _data.value =
                        _data.value.copy(
                            expireRemindEnabled = !enabled,
                            remindSync = RemindSync.Idle,
                            errorMessageRes = R.string.settings_remind_save_failed,
                        )
                }
        }
    }

    fun setTrafficRemind(enabled: Boolean) {
        if (_data.value.remindSync != RemindSync.Idle) return
        _data.value = _data.value.copy(trafficRemindEnabled = enabled, remindSync = RemindSync.Saving, errorMessageRes = null)
        viewModelScope.launch {
            authRepository
                .updateRemindTraffic(enabled)
                .onSuccess {
                    _data.value = _data.value.copy(remindSync = RemindSync.Idle)
                }.onFailure {
                    _data.value =
                        _data.value.copy(
                            trafficRemindEnabled = !enabled,
                            remindSync = RemindSync.Idle,
                            errorMessageRes = R.string.settings_remind_save_failed,
                        )
                }
        }
    }

    fun showChangePassword() {
        _changePasswordState.value = ChangePasswordState.Editing()
    }

    fun dismissChangePassword() {
        _changePasswordState.value = ChangePasswordState.Closed
    }

    fun onOldPasswordChange(value: String) {
        _changePasswordState.updateEditing { it.copy(oldPassword = value) }
    }

    fun onNewPasswordChange(value: String) {
        _changePasswordState.updateEditing { it.copy(newPassword = value) }
    }

    fun onConfirmPasswordChange(value: String) {
        _changePasswordState.updateEditing { it.copy(confirmPassword = value) }
    }

    private fun MutableStateFlow<ChangePasswordState>.updateEditing(transform: (ChangePasswordForm) -> ChangePasswordForm) {
        val current = value as? ChangePasswordState.Editing ?: return
        value = current.copy(form = transform(current.form))
    }

    fun submitChangePassword() {
        val state = _changePasswordState.value as? ChangePasswordState.Editing ?: return
        if (state.submitting) return
        val form = state.form
        val error =
            when {
                form.oldPassword.isBlank() -> R.string.settings_change_pwd_old_required
                form.newPassword.length < 8 -> R.string.settings_change_pwd_too_short
                form.newPassword != form.confirmPassword -> R.string.settings_change_pwd_mismatch
                else -> null
            }
        if (error != null) {
            _tip.value = SubmitTip(messageRes = error)
            return
        }
        _changePasswordState.value = state.copy(submitting = true)
        viewModelScope.launch {
            authRepository
                .changePassword(form.oldPassword, form.newPassword)
                .onSuccess { outcome ->
                    _changePasswordState.value = ChangePasswordState.Closed
                    val message = when (outcome) {
                        PasswordChangeOutcome.SESSION_RESTORED -> R.string.settings_change_pwd_success
                        PasswordChangeOutcome.SIGN_IN_REQUIRED -> R.string.settings_change_pwd_sign_in_required
                    }
                    _tip.value = SubmitTip(messageRes = message)
                }.onFailure {
                    val editing = _changePasswordState.value as? ChangePasswordState.Editing
                    if (editing != null) {
                        _changePasswordState.value = editing.copy(submitting = false)
                    }
                    _tip.value = SubmitTip(messageRes = R.string.settings_change_pwd_failed)
                }
        }
    }

    fun clearTip() {
        _tip.value = null
    }

    private companion object {
        const val SWITCH_STOP_TIMEOUT_MS = 10000L
    }
}
