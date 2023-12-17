/*
 * Copyright (C) 2023 the risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.statusbar.phone

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.AsyncTask
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

import com.android.systemui.R
import com.android.systemui.statusbar.policy.ConfigurationController

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FaceUnlockImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    private val DELAY_HIDE_DURATION = 1500

    enum class State {
        SCANNING, NOT_VERIFIED, SUCCESS, HIDDEN
    }

    private var currentState: State = State.HIDDEN
    private val dismissAnimation: ObjectAnimator = createDismissAnimation()
    private val scanningAnimation: ObjectAnimator = createScanningAnimation()
    private val successAnimation: ObjectAnimator = createSuccessRotationAnimation()
    private val failureShakeAnimation: AnimatorSet = createShakeAnimation(10f)
    private val handler = Handler()
    private val vibrator: Vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    companion object {
        private lateinit var configurationController: ConfigurationController
        private var instance: FaceUnlockImageView? = null

        @JvmStatic
        fun setConfigurationController(cfgController: ConfigurationController) {
            this.configurationController = cfgController
        }

        fun getConfigurationController(): ConfigurationController? {
            return this.configurationController
        }

        @JvmStatic
        fun setBouncerState(state: State) {
            instance?.postDelayed({
                instance?.setState(state)
            }, 100)
        }

        @JvmStatic
        fun setInstance(instance: FaceUnlockImageView) {
            this.instance = instance
        }
    }

    init {
        visibility = View.GONE
        updateFaceIconState()
    }

    public override fun onAttachedToWindow() {
        setInstance(this)
        getConfigurationController()?.addCallback(configurationChangedListener)
    }

    public override fun onDetachedFromWindow() {
        getConfigurationController()?.removeCallback(configurationChangedListener)
    }

    private val configurationChangedListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onUiModeChanged() {
                updateColor()
            }
            override fun onThemeChanged() {
                updateColor()
            }
    }

    fun updateColor() {
        val isDark = (context.resources.configuration.uiMode
                and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val darkColor = context.getColor(R.color.island_background_color_dark)
        val lightColor = context.getColor(R.color.island_background_color_light)
        if ((this as? View)?.id == R.id.bouncer_face_unlock_icon) {
            imageTintList = ColorStateList.valueOf(if (isDark) lightColor else darkColor)
        } else {
            imageTintList = ColorStateList.valueOf(Color.parseColor("#FFFFFF"))
        }
    }

    fun setState(state: State) {
        if (currentState != state) {
            currentState = state
            updateFaceIconState()
            handleAnimationForState(state)
        }
    }

    private fun updateFaceIconState() {
        setImageResource(when (currentState) {
            State.SCANNING -> R.drawable.face_scanning
            State.NOT_VERIFIED -> R.drawable.face_not_verified
            State.SUCCESS -> R.drawable.face_success
            State.HIDDEN -> 0
        })
    }

    private fun startOvershootAnimation() {
        val overshootAnimator = ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofFloat("scaleX", 0f, 1.2f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 0f, 1.2f, 1f)
        )
        overshootAnimator.duration = 500
        overshootAnimator.interpolator = OvershootInterpolator(1.5f)
        overshootAnimator.start()
    }


    private fun createScanningAnimation(): ObjectAnimator {
        val scaleX = PropertyValuesHolder.ofFloat("scaleX", 1f, 1.2f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat("scaleY", 1f, 1.2f, 1f)
        val scanningAnimator = ObjectAnimator.ofPropertyValuesHolder(this, scaleX, scaleY)
        scanningAnimator.duration = 1000
        scanningAnimator.repeatCount = ObjectAnimator.INFINITE
        scanningAnimator.interpolator = LinearInterpolator()
        return scanningAnimator
    }

    private fun createSuccessRotationAnimation(): ObjectAnimator {
        val flipRotation = PropertyValuesHolder.ofFloat("rotationY", 0f, 360f)
        val flipAnimator = ObjectAnimator.ofPropertyValuesHolder(this, flipRotation)
        flipAnimator.duration = 800
        flipAnimator.interpolator = AccelerateDecelerateInterpolator()
        return flipAnimator
    }

    private fun createShakeAnimation(amplitude: Float): AnimatorSet {
        val animatorSet = AnimatorSet()
        val translationX = PropertyValuesHolder.ofFloat("translationX", 0f, amplitude, -amplitude, amplitude, -amplitude, 0f)
        val shakeAnimator = ObjectAnimator.ofPropertyValuesHolder(this, translationX)
        shakeAnimator.duration = 500
        shakeAnimator.interpolator = AccelerateDecelerateInterpolator()
        animatorSet.play(shakeAnimator)
        return animatorSet
    }

    private fun createDismissAnimation(): ObjectAnimator {
        return ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofFloat("scaleX", 1f, 0f),
            PropertyValuesHolder.ofFloat("scaleY", 1f, 0f)
        ).apply {
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                }
            })
        }
    }

    private fun vibrate(effect: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            val vibrationEffect = VibrationEffect.createPredefined(effect)
            vibrator.vibrate(vibrationEffect)
        }
    }

    private fun handleAnimationForState(state: State) {
        when (state) {
            State.SCANNING -> {
                visibility = View.VISIBLE
                failureShakeAnimation.cancel()
                successAnimation.cancel()
                startOvershootAnimation()
                handler.post({ scanningAnimation.start() })
            }
            State.NOT_VERIFIED -> {
                scanningAnimation.cancel()
                successAnimation.cancel()
                failureShakeAnimation.start()
                vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
            }
            State.SUCCESS -> {
                scanningAnimation.cancel()
                failureShakeAnimation.cancel()
                successAnimation.start()
                vibrate(VibrationEffect.EFFECT_CLICK)
            }
            State.HIDDEN -> {
                failureShakeAnimation.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        handler.post({ dismissAnimation.start() })
                    }
                })
                successAnimation.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        handler.post({ dismissAnimation.start() })
                    }
                })
            }
        }
    }
}
