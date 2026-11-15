package com.satnova.alpha

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FavoriteProfileTest {
    private fun channel(id:String,name:String,radio:Int=0) =
        RemoteChannel(id,name,1,radio,false,null,null)

    @Test fun tvAndRadioGroupsAndManualMembershipSurviveReload() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("satnova_remote_favorites",Context.MODE_PRIVATE)
            .edit().clear().commit()
        val receiver="192.168.1.2"

        val profile=FavoriteProfile(context)
        profile.useReceiver(receiver,false)
        profile.sync(listOf(channel("tv1","MBC 1 HD"),channel("tv2","MBC 2 HD")))
        assertEquals("family:mbc",profile.list().first().key)
        val custom=profile.createCustom("الرياضة")!!
        assertTrue(profile.addMember(custom,"tv1"))
        assertTrue(profile.addMember(custom,"tv2"))
        assertFalse(profile.addMember(custom,"tv2"))

        profile.useReceiver(receiver,true)
        profile.sync(listOf(channel("radio1","Radio X",1)))
        assertTrue(profile.list().all{it.key.startsWith("radio:")})
        val radioCustom=profile.createCustom("إذاعاتي")!!
        assertTrue(profile.addMember(radioCustom,"radio1"))
        assertFalse(profile.members(radioCustom).contains("tv1"))

        val restored=FavoriteProfile(context)
        restored.useReceiver(receiver,false)
        assertTrue(restored.list().any{it.key==custom})
        assertEquals(setOf("tv1","tv2"),restored.members(custom))
        restored.useReceiver(receiver,true)
        assertTrue(restored.list().any{it.key==radioCustom})
        assertEquals(setOf("radio1"),restored.members(radioCustom))
        restored.removeMember(radioCustom,"radio1")
        assertTrue(restored.members(radioCustom).isEmpty())
    }
}
