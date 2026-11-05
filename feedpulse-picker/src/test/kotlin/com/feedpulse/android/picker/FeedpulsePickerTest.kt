package com.feedpulse.android.picker

import android.app.Activity
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import android.widget.FrameLayout
import com.facebook.react.views.debuggingoverlay.DebuggingOverlay

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedpulsePickerTest {
    private val results = ArrayList<PickResult>()
    private var clicked = false

    private fun launch(build: (Activity, LinearLayout) -> Unit): Activity {
        val c = Robolectric.buildActivity(Activity::class.java).create()
        val a = c.get()
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        build(a, col)
        a.setContentView(col, ViewGroup.LayoutParams(600, 1000))
        c.start().resume().visible()
        ShadowLooper.idleMainLooper()
        return a
    }

    private fun LinearLayout.add(v: View, h: Int = 100): View {
        addView(v, LinearLayout.LayoutParams(500, h)); return v
    }

    private fun pick(a: Activity, o: PickerOptions = PickerOptions()) =
        Feedpulse.pickTarget(a, o) { results.add(it) }

    private fun overlayOf(a: Activity): View {
        val d = a.window.decorView as ViewGroup
        return d.getChildAt(d.childCount - 1)
    }

    private fun tapCenter(a: Activity, v: View) {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val x = loc[0] + v.width / 2f; val y = loc[1] + v.height / 2f
        val o = overlayOf(a); val t = SystemClock.uptimeMillis()
        o.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        o.dispatchTouchEvent(MotionEvent.obtain(t, t + 10, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun selected() = (results.single() as PickResult.Selected).target

    private fun assertValid(t: FeedpulseTarget) =
        assertEquals(emptyList<String>(), TargetSchema.violations(t.toMap()))

    @Test fun view_auto_capture_uses_class_resource_name_text_and_rect() {
        lateinit var btn: View
        val a = launch { act, col ->
            btn = col.add(Button(act).apply { id = 0x7f0b0001; text = "  Pay\nnow  " })
            btn.setOnClickListener { clicked = true }
        }
        pick(a, PickerOptions(screenName = "Checkout"))
        tapCenter(a, btn)
        val t = selected()
        assertEquals("Checkout", t.screen)
        assertNull(t.id)
        assertEquals("Pay now", t.label)
        assertTrue(t.path!!.endsWith("Button"))
        assertEquals(500.0, t.rect!!.w, 0.0)
        assertEquals("android", t.platform)
        assertFalse("overlay must swallow the tap", clicked)
        assertValid(t)
    }

    @Test fun label_is_truncated_to_first_n_chars() {
        lateinit var tv: View
        val a = launch { act, col -> tv = col.add(TextView(act).apply { text = "abcdefghij" }) }
        pick(a, PickerOptions(labelMaxChars = 4))
        tapCenter(a, tv)
        assertEquals("abcd", selected().label)
    }

    @Test fun edit_text_value_is_never_captured() {
        lateinit var et: View
        val a = launch { act, col -> et = col.add(EditText(act).apply { setText("secret-value"); hint = "hint-text" }) }
        pick(a); tapCenter(a, et)
        val t = selected()
        assertNull(t.label)
        assertFalse(t.toJson().contains("secret-value"))
        assertFalse(t.toJson().contains("hint-text"))
    }

    @Test fun password_input_text_is_never_captured() {
        lateinit var pw: View
        val a = launch { act, col ->
            pw = col.add(EditText(act).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setText("hunter2")
                contentDescription = "Password field"
            })
        }
        pick(a); tapCenter(a, pw)
        val t = selected()
        assertEquals("Password field", t.label) // 開発者が付けた contentDescription のみ
        assertFalse(t.toJson().contains("hunter2"))
    }

    @Test fun explicit_id_beats_auto_capture_even_on_ancestor() {
        lateinit var inner: View
        val a = launch { act, col ->
            val card = LinearLayout(act).apply { feedpulseId("checkout.card") }
            inner = Button(act).apply { id = 0x7f0b0002; text = "Pay" }
            card.addView(inner, LinearLayout.LayoutParams(400, 80))
            col.add(card, 200)
        }
        pick(a); tapCenter(a, inner)
        val t = selected()
        assertEquals("checkout.card", t.id)
        assertEquals(500.0, t.rect!!.w, 0.0) // 選ばれたのは card
        assertTrue(t.path!!.endsWith("LinearLayout"))
        assertValid(t)
    }

    @Test fun innermost_explicit_id_wins() {
        lateinit var inner: View
        val a = launch { act, col ->
            val card = LinearLayout(act).apply { feedpulseId("outer") }
            inner = Button(act).apply { feedpulseId("inner") }
            card.addView(inner, LinearLayout.LayoutParams(400, 80))
            col.add(card, 200)
        }
        pick(a); tapCenter(a, inner)
        assertEquals("inner", selected().id)
    }

    @Test fun secure_window_is_unavailable_and_adds_no_overlay() {
        val a = launch { act, col ->
            act.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            col.add(Button(act))
        }
        val before = (a.window.decorView as ViewGroup).childCount
        val h = pick(a)
        assertEquals(PickResult.Unavailable(PickResult.Reason.SECURE_WINDOW), results.single())
        assertFalse(h.isActive)
        assertEquals(before, (a.window.decorView as ViewGroup).childCount)
    }

    @Test fun cancel_removes_overlay_and_calls_back_once() {
        val a = launch { act, col -> col.add(Button(act)) }
        val before = (a.window.decorView as ViewGroup).childCount
        val h = pick(a)
        assertTrue(h.isActive)
        h.cancel(); h.cancel()
        assertEquals(listOf<PickResult>(PickResult.Cancelled), results)
        assertEquals(before, (a.window.decorView as ViewGroup).childCount)
    }

    @Test fun second_pick_cancels_the_first() {
        val a = launch { act, col -> col.add(Button(act)) }
        pick(a); pick(a)
        assertEquals(listOf<PickResult>(PickResult.Cancelled), results)
    }

    @Test fun invisible_views_are_skipped() {
        lateinit var shown: View
        val a = launch { act, col ->
            shown = col.add(Button(act).apply { text = "shown" })
            (col.add(Button(act).apply { text = "gone"; feedpulseId("hidden") }) as View).visibility = View.GONE
        }
        pick(a); tapCenter(a, shown)
        assertEquals("shown", selected().label)
    }

    @Test fun long_values_are_clamped_to_schema_limits() {
        lateinit var v: View
        val a = launch { act, col -> v = col.add(Button(act).apply { text = "x".repeat(500); feedpulseId("i".repeat(500)) }) }
        pick(a, PickerOptions(labelMaxChars = 999, screenName = "s".repeat(999)))
        tapCenter(a, v)
        assertValid(selected())
    }

    @Test fun json_matches_issue_shape() {
        val t = FeedpulseTarget("Home", id = "a\"b", rect = FeedpulseRect(1.0, 2.0, 3.0, 4.0), label = "x")
        assertEquals(
            """{"platform":"android","screen":"Home","id":"a\"b","rect":{"x":1.0,"y":2.0,"w":3.0,"h":4.0},"label":"x"}""",
            t.toJson(),
        )
    }


    @Test fun react_native_debugging_overlay_is_skipped() {
        lateinit var btn: View
        val a = launch { act, col ->
            val frame = FrameLayout(act)
            btn = Button(act).apply { id = 0x7f0b0002; text = "Pay" }
            frame.addView(btn, FrameLayout.LayoutParams(500, 100))
            frame.addView(DebuggingOverlay(act), FrameLayout.LayoutParams(500, 100))
            col.addView(frame, LinearLayout.LayoutParams(500, 100))
        }
        pick(a)
        tapCenter(a, btn)
        assertEquals("Pay", selected().label)
    }
}
