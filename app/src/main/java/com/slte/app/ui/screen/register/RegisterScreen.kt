package com.slte.app.ui.screen.register

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slte.app.R
import com.slte.app.domain.model.EmailWhitelist
import com.slte.app.ui.component.AnimatedSticker
import com.slte.app.ui.component.LoadingOverlay
import com.slte.app.ui.component.SlteButton
import com.slte.app.ui.component.SlteButtonStyle
import com.slte.app.ui.component.SlteInput
import com.slte.app.ui.component.SlteOptionMenu
import com.slte.app.ui.component.SlteOptionMenuItem
import com.slte.app.ui.component.SltePasswordInput
import com.slte.app.ui.component.ToastTip
import com.slte.app.ui.theme.SlteColors
import com.slte.app.ui.theme.SlteIcons
import com.slte.app.ui.theme.SlteType
import com.slte.app.utils.Dimens
import com.slte.app.utils.Stickers

@Composable
fun RegisterScreen(
    emailVerifyEnabled: Boolean,
    inviteForceEnabled: Boolean,
    onBackToLogin: () -> Unit,
    viewModel: RegisterViewModel = hiltViewModel(),
) {
    LaunchedEffect(emailVerifyEnabled, inviteForceEnabled) {
        viewModel.initConfig(emailVerifyEnabled, inviteForceEnabled)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current

    val form =
        when (val s = state) {
            is RegisterUiState.Form -> s
            is RegisterUiState.SendingCode -> s.form
            is RegisterUiState.Countdown -> s.form
            is RegisterUiState.Registering -> s.form
            is RegisterUiState.RegisterSuccess -> s.form
            is RegisterUiState.Error -> s.form
        }

    val isSendingCode = state is RegisterUiState.SendingCode
    val isRegistering = state is RegisterUiState.Registering
    val countdownSeconds = (state as? RegisterUiState.Countdown)?.seconds ?: 0
    val errorMessageRes = (state as? RegisterUiState.Error)?.messageRes
    val isCountingDown = state is RegisterUiState.Countdown
    val isLoading = isSendingCode

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = Dimens.gap.xxl,
                vertical = Dimens.gap.xxl,
            ),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.widthIn(max = Dimens.maxContentWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Dimens.gap.xxl))

            AnimatedSticker(
                assetPath = Stickers.REGISTER,
                modifier = Modifier.size(Dimens.logoSize),
            )

            Spacer(modifier = Modifier.height(Dimens.gap.xl))

            Text(
                text = stringResource(R.string.register_title),
                style = SlteType.pageTitle,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(Dimens.gap.xxl))

            if (form.emailWhitelist.isEnabled) {
                // 一个字段里左右分栏：左边填邮箱名，竖线右边是后缀选择
                EmailSuffixField(
                    localPart = form.email.substringBeforeLast('@'),
                    whitelist = form.emailWhitelist,
                    selectedSuffix = form.emailSuffix,
                    onLocalPartChange = viewModel::onEmailChange,
                    onSelectSuffix = viewModel::onEmailSuffixChange,
                    enabled = !isRegistering,
                )
            } else {
                SlteInput(
                    value = form.email,
                    onValueChange = viewModel::onEmailChange,
                    placeholder = stringResource(R.string.error_email_required),
                    modifier = Modifier.fillMaxWidth(),
                    icon = SlteIcons.Account,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    enabled = !isRegistering,
                    bordered = false,
                )
            }

            Spacer(modifier = Modifier.height(Dimens.gap.md))

            if (emailVerifyEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.gap.md),
                ) {
                    SlteInput(
                        value = form.verificationCode,
                        onValueChange = viewModel::onCodeChange,
                        placeholder = stringResource(R.string.error_code_required),
                        modifier = Modifier.weight(1f),
                        icon = SlteIcons.VerificationCode,
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                        enabled = !isRegistering,
                        bordered = false,
                    )
                    SlteButton(
                        text =
                        if (isCountingDown) {
                            stringResource(R.string.format_countdown_s, countdownSeconds)
                        } else {
                            stringResource(R.string.register_send_code)
                        },
                        onClick = viewModel::sendVerificationCode,
                        modifier = Modifier.width(Dimens.sendCodeButtonWidth),
                        style = SlteButtonStyle.Tonal,
                        enabled = !isCountingDown,
                        loading = isLoading,
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.gap.md))
            }

            SltePasswordInput(
                value = form.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = stringResource(R.string.error_password_required),
                modifier = Modifier.fillMaxWidth(),
                icon = SlteIcons.Password,
                imeAction = ImeAction.Done,
                enabled = !isRegistering,
                bordered = false,
            )

            Spacer(modifier = Modifier.height(Dimens.gap.md))

            SlteInput(
                value = form.inviteCode,
                onValueChange = viewModel::onInviteCodeChange,
                placeholder =
                if (inviteForceEnabled) {
                    stringResource(R.string.register_invite_hint)
                } else {
                    stringResource(R.string.register_invite_optional)
                },
                modifier = Modifier.fillMaxWidth(),
                icon = SlteIcons.InviteCode,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                enabled = !isRegistering,
                bordered = false,
            )

            Spacer(modifier = Modifier.height(Dimens.gap.lg))
            SlteButton(
                text = stringResource(R.string.register_button),
                onClick = viewModel::register,
                modifier = Modifier.fillMaxWidth(),
                style = SlteButtonStyle.Primary,
                enabled = !isRegistering,
                height = Dimens.size.row,
            )

            Spacer(modifier = Modifier.height(Dimens.gap.md))

            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onBackToLogin()
            }) {
                Text(stringResource(R.string.register_back_to_login))
            }
        }
    }

    LoadingOverlay(visible = isRegistering, onDismiss = viewModel::cancelLoading)

    ToastTip(
        message = errorMessageRes?.let { stringResource(it) },
        onDismiss = viewModel::dismissError,
    )
}

/**
 * 邮箱字段（白名单启用时）：左边填 `@` 前面的部分，竖线右边是后缀选择。
 *
 * 后缀只能从后端下发的列表里选，避免"输入完才说不允许"；弹窗与语言切换同款。
 */

@Composable
private fun EmailSuffixField(
    localPart: String,
    whitelist: EmailWhitelist,
    selectedSuffix: String?,
    onLocalPartChange: (String) -> Unit,
    onSelectSuffix: (String) -> Unit,
    enabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    val haptic = LocalHapticFeedback.current

    SlteInput(
        value = localPart,
        onValueChange = onLocalPartChange,
        placeholder = stringResource(R.string.register_email_local_placeholder),
        modifier = Modifier.fillMaxWidth(),
        icon = SlteIcons.Account,
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        enabled = enabled,
        bordered = false,
        trailingDivider = true,
        trailing = {
            Row(
                modifier =
                Modifier
                    // 竖线右侧这一段：跟随字段收放，但只占一部分宽度，别顶到最右边
                    .weight(SUFFIX_SECTION_WEIGHT)
                    .onGloballyPositioned { anchorBounds = it.boundsInWindow() }
                    .clickable(enabled = enabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        expanded = true
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = "@${selectedSuffix.orEmpty()}",
                    style = SlteType.body,
                    color = if (enabled) SlteColors.current.accentInteractive else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    // 正常情况不会触发；后端若下发超长后缀时兜底，避免顶掉箭头图标
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) SlteIcons.ExpandLess else SlteIcons.ExpandMore,
                    contentDescription = stringResource(R.string.register_email_suffix_label),
                    modifier = Modifier.size(Dimens.icon.md),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )

    SlteOptionMenu(
        expanded = expanded,
        anchorBounds = anchorBounds,
        onDismissRequest = { expanded = false },
    ) {
        whitelist.suffixes.forEach { suffix ->
            SlteOptionMenuItem(
                label = "@$suffix",
                selected = suffix == selectedSuffix,
                onClick = {
                    onSelectSuffix(suffix)
                    expanded = false
                },
            )
        }
    }
}

/**
 * 竖线右侧占的宽度比例：与左侧等宽，分割线落在字段正中间。
 * 之前 0.8f 时后缀区放不下 @gmail.com 这类长后缀（.com 被裁掉），加宽到 1f 后完整显示；
 * 分割线从"中间偏右"回到正中，视觉上整体只向左移了一点。
 */
private const val SUFFIX_SECTION_WEIGHT = 1f
