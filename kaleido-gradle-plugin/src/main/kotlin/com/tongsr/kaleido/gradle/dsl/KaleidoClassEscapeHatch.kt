package com.tongsr.kaleido.gradle.dsl

import javax.inject.Inject

abstract class KaleidoClassEscapeHatch @Inject constructor(name: String) : KaleidoEscapeHatch(name)
