package nodomain.freeyourgadget.gadgetbridge.widgets.impl

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.health.HealthAnalysis
import nodomain.freeyourgadget.gadgetbridge.health.HealthReport
import nodomain.freeyourgadget.gadgetbridge.health.HealthReportActivity
import nodomain.freeyourgadget.gadgetbridge.health.HealthSummaryView
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.widgets.GBWidget
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetConfig
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetDataScope
import nodomain.freeyourgadget.gadgetbridge.widgets.WidgetVariant

/**
 * The health condition of the last four weeks, built from the heart rate and the other sensors of the band, with a
 * tap through to the full report. See [HealthAnalysis] for what is measured and [HealthReportActivity] for the detail.
 */
object HealthWidget : GBWidget<HealthReport> {
    override val id = "health_report"
    override val label = R.string.health_title
    override val icon = R.drawable.ic_heartrate
    override val variants = listOf(WidgetVariant("summary", R.string.widget_variant_health, 6, 470))
    override val supportsLauncher = false

    override fun isSupportedBy(device: GBDevice): Boolean =
        device.deviceCoordinator.supportsHeartRateMeasurement(device)

    override suspend fun loadData(scope: WidgetDataScope, config: WidgetConfig): HealthReport =
        HealthAnalysis.load(scope.devices, scope.day)

    override fun previewData(): HealthReport = HealthReport.preview()

    override fun createView(inflater: LayoutInflater, parent: ViewGroup, variant: WidgetVariant): View =
        HealthSummaryView(inflater.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    override fun bind(view: View, variant: WidgetVariant, config: WidgetConfig, data: HealthReport) {
        (view as HealthSummaryView).setReport(data)
    }

    override fun bindLoading(view: View, variant: WidgetVariant, config: WidgetConfig) {
        (view as HealthSummaryView).setLoading()
    }

    override fun onClick(view: View, variant: WidgetVariant, config: WidgetConfig, timestamp: Int) {
        HealthReportActivity.open(view.context)
    }
}
