package com.feedpulse.android.compose

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.feedpulse.android.picker.Feedpulse
import com.feedpulse.android.picker.FeedpulseTarget
import com.feedpulse.android.picker.PickResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposePickerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val results = ArrayList<PickResult>()

    private fun pickAndTap(tag: String): FeedpulseTarget {
        rule.runOnUiThread { Feedpulse.pickTarget(rule.activity) { results.add(it) } }
        rule.waitForIdle()
        val node = rule.onNodeWithTag(tag)
        val b = node.fetchSemanticsNode().boundsInRoot
        val root = rule.activity.window.decorView
        val loc = IntArray(2)
        // compose view の画面オフセットを足す
        val cv = findComposeView(root)
        cv.getLocationOnScreen(loc)
        val x = loc[0] + (b.left + b.right) / 2; val y = loc[1] + (b.top + b.bottom) / 2
        val decor = root as ViewGroup
        val overlay = decor.getChildAt(decor.childCount - 1)
        rule.runOnUiThread {
            val t = SystemClock.uptimeMillis()
            overlay.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0))
        }
        return (results.single() as PickResult.Selected).target
    }

    private fun findComposeView(v: View): View = find(v) ?: error("no compose view")

    private fun find(v: View): View? {
        if (v is androidx.compose.ui.platform.ViewRootForTest) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun modifier_feedpulseId_is_returned_as_target_id() {
        rule.setContent {
            Column {
                Box(Modifier.size(100.dp).feedpulseId("checkout.pay")) { BasicText("Pay now", Modifier.testTag("pay")) }
            }
        }
        val t = pickAndTap("pay")
        assertEquals("checkout.pay", t.id)
        assertEquals("android", t.platform)
        assertNotNull(t.rect)
        assertTrue(t.rect!!.w > 0 && t.rect!!.h > 0)
    }

    @Test fun without_explicit_id_falls_back_to_semantics_text_and_tag() {
        rule.setContent { Box(Modifier.size(100.dp)) { BasicText("Hello world", Modifier.testTag("t")) } }
        val t = pickAndTap("t")
        assertNull(t.id)
        assertEquals("Hello world", t.label)
        assertTrue(t.path!!.contains("Compose#t"))
    }

    @Test fun text_field_value_is_never_captured() {
        rule.setContent {
            var v by remember { mutableStateOf("secret-compose-value") }
            Box(Modifier.size(100.dp)) { BasicTextField(v, { v = it }, Modifier.testTag("field").size(100.dp)) }
        }
        val t = pickAndTap("field")
        assertNull(t.label)
        assertFalse(t.toJson().contains("secret-compose-value"))
    }

    @Test fun explicit_id_on_ancestor_beats_inner_auto_capture() {
        rule.setContent {
            Box(Modifier.size(100.dp).feedpulseId("outer")) { BasicText("inner", Modifier.testTag("in")) }
        }
        assertEquals("outer", pickAndTap("in").id)
    }
}
