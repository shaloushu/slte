package com.slte.app.kernel

import android.content.Context
import com.slte.app.BuildConfig
import com.slte.app.kernel.api.KernelApi
import com.slte.app.kernel.api.KernelProfile
import com.slte.app.kernel.api.KernelProfiles
import com.slte.app.kernel.api.KernelType
import com.slte.app.support.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KernelConfigTest {
    @get:Rule
    val mainRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val manager = mockk<KernelApi>(relaxed = true)
    private val profiles = mockk<KernelProfiles>(relaxed = true)
    private val subscribeSource = mockk<SubscribeSource>(relaxed = true)
    private val remoteConfig = mockk<AppRemoteConfig>(relaxed = true)
    private val reporter = KernelFaultReporter(mainRule.dispatcher)

    private val email = "a@b.c"
    private val apiBaseUrl = "https://app.example.com"
    private val subscribeUrl = apiBaseUrl + BuildConfig.SUBSCRIBE_PATH
    private val expectedDomains = listOf("example.com", "example.net")

    private val yaml =
        """
        proxies:
          - name: "jp-01"
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-128-gcm
            password: "x"
        rules:
          - MATCH,节点选择
        """.trimIndent()

    @Test
    fun `切换到智能内核后重导出配置并注入预设`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        every { manager.type } returns KernelType.MIHOMO_SMART
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { profiles.queryByUUID(uuid) } returns profile(uuid)
        coEvery { profiles.queryActive() } returns profile(uuid)
        importedFile(uuid).writeText(SubscriptionSanitizer.sanitize(smartSourceYaml, expectedDomains))
        tmp.root.resolve("imported/$uuid/.slte-kernel").writeText("mihomo")

        cfg.ensureProfile()

        assertTrue("应注入自动选择组", importedFile(uuid).readText().contains("自动选择"))
        assertEquals("mihomo_smart", tmp.root.resolve("imported/$uuid/.slte-kernel").readText())
        assertTrue("pristine 副本应存在", tmp.root.resolve("imported/$uuid/config.pristine").exists())
        coVerify { manager.notifyProfileChanged(uuid) }
    }

    @Test
    fun `同内核类型连接不触发内核重载`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { profiles.queryByUUID(uuid) } returns profile(uuid)
        coEvery { profiles.queryActive() } returns profile(uuid)
        val sanitized = SubscriptionSanitizer.sanitize(smartSourceYaml, expectedDomains)
        importedFile(uuid).writeText(sanitized)
        tmp.root.resolve("imported/$uuid/config.pristine").writeText(sanitized)
        tmp.root.resolve("imported/$uuid/.slte-kernel").writeText("mihomo")

        cfg.ensureProfile()

        coVerify(exactly = 0) { manager.notifyProfileChanged(any()) }
    }

    private val smartSourceYaml =
        """
        proxies:
          - name: "jp-01"
            type: ss
            server: 1.2.3.4
            port: 8388
        proxy-groups:
          - name: 节点选择
            type: select
            proxies:
              - jp-01
        rules:
          - MATCH,节点选择
        """.trimIndent()

    private fun config(
        baseUrl: String = apiBaseUrl,
        domains: List<String> = listOf("example.net"),
    ): KernelConfig {
        every { context.filesDir } returns tmp.root
        every { manager.type } returns KernelType.MIHOMO
        coEvery { manager.awaitProfile() } returns profiles
        every { remoteConfig.apiBaseUrl } returns baseUrl
        every { remoteConfig.directDomains } returns domains
        every { subscribeSource.getEmail() } returns email
        return KernelConfig(reporter, manager, subscribeSource, remoteConfig, context)
    }

    private fun profile(
        uuid: UUID,
        name: String = profileNameFor(email),
        source: String = subscribeUrl,
        imported: Boolean = true,
    ) = KernelProfile(uuid = uuid, name = name, source = source, imported = imported)

    private fun body(text: String) = text.toResponseBody("application/yaml".toMediaType())

    private fun importedFile(uuid: UUID): File = tmp.root.resolve("imported/$uuid/config.yaml").apply { parentFile?.mkdirs() }

    @Test
    fun `订阅内容未变化时跳过内核重载`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { subscribeSource.fetchSubscribeYaml() } returns body(yaml)
        importedFile(uuid).writeText(SubscriptionSanitizer.sanitize(yaml, expectedDomains))

        assertEquals(ProfileUpdateResult.UNCHANGED, cfg.updateProfile())

        verify { subscribeSource.saveSubscriptionUpdatedAt() }
    }

    @Test
    fun `清洗结果被安全拒绝时保留原有配置`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { subscribeSource.fetchSubscribeYaml() } returns
            body("? hosts\n: {bank.example: 1.2.3.4}\nproxies:\n  - name: \"x\"\n")
        importedFile(uuid).writeText(yaml)

        assertEquals(ProfileUpdateResult.FAILED, cfg.updateProfile())
        assertEquals("清洗失败时不得覆盖已有配置", yaml, importedFile(uuid).readText())
    }

    @Test
    fun `拿不到订阅体时失败`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { subscribeSource.fetchSubscribeYaml() } returns null

        assertEquals(ProfileUpdateResult.FAILED, cfg.updateProfile())
    }

    @Test
    fun `订阅响应不是 Clash 配置时拒绝写入`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        coEvery { profiles.queryAll() } returns listOf(profile(uuid))
        coEvery { subscribeSource.fetchSubscribeYaml() } returns body("<html>502 Bad Gateway</html>")

        assertEquals(ProfileUpdateResult.FAILED, cfg.updateProfile())
        assertFalse(importedFile(uuid).exists())
    }

    @Test
    fun `直连域为空时拒绝导入`() = runTest(mainRule.dispatcher) {
        val cfg = config(baseUrl = "", domains = emptyList())
        coEvery { profiles.queryAll() } returns emptyList()
        coEvery { profiles.create(any(), any(), any()) } returns UUID.randomUUID()
        coEvery { subscribeSource.fetchSubscribeYaml() } returns body(yaml)

        assertEquals(ProfileUpdateResult.FAILED, cfg.updateProfile())
        coVerify(exactly = 0) { profiles.commit(any()) }
    }

    @Test
    fun `删除账号配置时按名字清理并保留其它配置`() = runTest(mainRule.dispatcher) {
        val cfg = config()
        val mine = UUID.randomUUID()
        val other = UUID.randomUUID()
        coEvery { profiles.queryAll() } returns
            listOf(
                profile(mine),
                profile(other, name = "SLTE-别人", source = "https://other.example/api"),
            )

        assertTrue(cfg.deleteAccountProfiles(email))
        coVerify { profiles.delete(mine) }
        coVerify(exactly = 0) { profiles.delete(other) }
    }

    @Test
    fun `无邮箱时不按共享订阅地址误删账号配置`() = runTest(mainRule.dispatcher) {
        val cfg = config()
        val accountA = UUID.randomUUID()
        val accountB = UUID.randomUUID()
        coEvery { profiles.queryAll() } returns
            listOf(
                profile(accountA, name = "账号A", source = "$subscribeUrl?token=a"),
                profile(accountB, name = "账号B", source = "$subscribeUrl?token=b"),
            )

        assertFalse(cfg.deleteAccountProfiles(null))
        coVerify(exactly = 0) { profiles.delete(any()) }
    }

    @Test
    fun `注入直连规则仅在内容需要改写时返回真`() = runTest(mainRule.dispatcher) {
        val uuid = UUID.randomUUID()
        val cfg = config()
        importedFile(uuid).writeText(yaml)

        assertTrue("缺少直连规则时应改写", cfg.injectDirectRule(uuid))
        assertFalse("已规范化后应跳过", cfg.injectDirectRule(uuid))
    }

    @Test
    fun `待导入目录不存在时注入直连规则返回假`() = runTest(mainRule.dispatcher) {
        val cfg = config()

        assertFalse(cfg.injectDirectRule(UUID.randomUUID()))
    }
}
