package com.dsh

import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import com.dsh.feature.settings.logic.AutoFreq
import com.dsh.feature.settings.logic.Automation
import com.dsh.feature.settings.logic.AutomationStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Разные автоматизации — разные задания, даже когда 16 бит хэша их id совпадают (QA DSH 1.1.0: DSH-12). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutomationJobIdsTest {
    private lateinit var app: Application
    private lateinit var js: JobScheduler

    // У этих UUID младшие 16 бит hashCode совпадают (23457).
    private val first = Automation("ea1ba378-456e-52cc-9e4d-84594c5b272a", "First", freq = AutoFreq.Hour)
    private val second = Automation("f82eec66-1bc9-53de-8788-c6cf350b7574", "Second", freq = AutoFreq.Hour)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        js = app.getSystemService(JobScheduler::class.java)
        js.cancelAll()
        AutomationStore.init(app)
    }

    private fun scheduledIds() = js.allPendingJobs.map { it.extras.getString("automation_id") }.sortedBy { it }

    @Test
    fun `colliding ids keep two separate jobs`() {
        assertEquals(first.id.hashCode() and 0xffff, second.id.hashCode() and 0xffff)
        AutomationScheduler.schedule(app, first)
        AutomationScheduler.schedule(app, second)
        assertEquals(listOf(first.id, second.id), scheduledIds())
    }

    @Test
    fun `rescheduling replaces only its own job`() {
        AutomationScheduler.schedule(app, first)
        AutomationScheduler.schedule(app, second)
        AutomationScheduler.schedule(app, second)
        AutomationScheduler.schedule(app, first)
        assertEquals(listOf(first.id, second.id), scheduledIds())
    }

    @Test
    fun `cancelling one automation leaves the other scheduled`() {
        AutomationScheduler.schedule(app, first)
        AutomationScheduler.schedule(app, second)
        AutomationScheduler.cancel(app, first.id)
        assertEquals(listOf(second.id), scheduledIds())
    }
}
