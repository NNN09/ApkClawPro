package com.apk.claw.android.compliance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPolicyStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun setUp() {
        AppPolicyStore.init(tmp.root)
    }

    @Test
    fun replaceAll_thenReload_roundTrips() {
        assertNull(AppPolicyStore.replaceAll(listOf(
            AppPolicy("com.tencent.mm", Mode.BLOCK),
            AppPolicy("com.alipay.android", Mode.CONFIRM),
            AppPolicy("com.android.settings", Mode.AUTO)
        )))
        AppPolicyStore.init(tmp.root)
        val loaded = AppPolicyStore.list()
        assertEquals(3, loaded.size)
        assertEquals(Mode.BLOCK, AppPolicyStore.find("com.tencent.mm")?.mode)
        assertEquals(Mode.CONFIRM, AppPolicyStore.find("com.alipay.android")?.mode)
        assertEquals(Mode.AUTO, AppPolicyStore.find("com.android.settings")?.mode)
        assertNull(AppPolicyStore.find("com.not.list"))
    }

    @Test
    fun replaceAll_rejectsInvalidPackage_keepsExisting() {
        assertNull(AppPolicyStore.replaceAll(listOf(AppPolicy("com.tencent.mm", Mode.BLOCK))))
        val error = AppPolicyStore.replaceAll(listOf(
            AppPolicy("com.tencent.mm", Mode.AUTO),
            AppPolicy("not-a-package", Mode.BLOCK)
        ))
        assertNotNull(error)
        // 原子性：非法条目导致整体拒绝，原表不变
        assertEquals(Mode.BLOCK, AppPolicyStore.find("com.tencent.mm")?.mode)
        assertEquals(1, AppPolicyStore.list().size)
    }

    @Test
    fun replaceAll_rejectsInvalidModeString_throughParseHelper() {
        assertEquals(Mode.BLOCK, AppPolicy.modeOf("block"))
        assertEquals(Mode.AUTO, AppPolicy.modeOf("Auto"))
        assertNull(AppPolicy.modeOf("WHITELIST"))
        assertNull(AppPolicy.modeOf(""))
        assertNull(AppPolicy.modeOf(null))
    }

    @Test
    fun replaceAll_enforcesMaxCount() {
        val items = (1..AppPolicy.MAX_POLICIES).map { AppPolicy("com.app$it.example", Mode.AUTO) }
        assertNull(AppPolicyStore.replaceAll(items))
        assertNotNull(AppPolicyStore.replaceAll(items + AppPolicy("com.overflow.example", Mode.AUTO)))
        assertEquals(AppPolicy.MAX_POLICIES, AppPolicyStore.list().size)
    }

    @Test
    fun replaceAll_emptyListClears() {
        assertNull(AppPolicyStore.replaceAll(listOf(AppPolicy("com.tencent.mm", Mode.BLOCK))))
        assertNull(AppPolicyStore.replaceAll(emptyList()))
        assertTrue(AppPolicyStore.list().isEmpty())
    }

    @Test
    fun packageNameValidation() {
        assertTrue(AppPolicy.isValidPackageName("com.tencent.mm"))
        assertTrue(AppPolicy.isValidPackageName("com.android.settings"))
        assertTrue(AppPolicy.isValidPackageName("a.b1_c"))
        assertTrue(!AppPolicy.isValidPackageName("noDot"))
        assertTrue(!AppPolicy.isValidPackageName(".leading.dot"))
        assertTrue(!AppPolicy.isValidPackageName("com..double"))
        assertTrue(!AppPolicy.isValidPackageName("1com.numeric.lead"))
        assertTrue(!AppPolicy.isValidPackageName("com.tencent.中文"))
        assertTrue(!AppPolicy.isValidPackageName(""))
    }
}
