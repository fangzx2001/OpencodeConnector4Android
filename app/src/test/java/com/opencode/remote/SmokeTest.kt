package com.opencode.remote

import app.cash.turbine.test
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {

    @Test
    fun junitWorks() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun mockkWorks() {
        val mock = mockk<SimpleInterface>(relaxed = true)
        mock.doStuff()
        verify { mock.doStuff() }
    }

    @Test
    fun turbineWorks() = runTest {
        val f = flow {
            emit(1)
            emit(2)
            emit(3)
        }

        f.test {
            assertEquals(1, awaitItem())
            assertEquals(2, awaitItem())
            assertEquals(3, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun coroutinesTestWorks() = runTest {
        var result = 0
        val job = launch {
            delay(10)
            result = 42
        }
        job.join()
        assertEquals(42, result)
    }

    private interface SimpleInterface {
        fun doStuff()
    }
}
