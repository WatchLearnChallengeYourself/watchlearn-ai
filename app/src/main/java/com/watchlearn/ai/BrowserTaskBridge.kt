package com.watchlearn.ai

/**
 * Connects the personal browser activity to the already-running WatchLearn AI
 * task composer without bringing the main screen to the foreground.
 */
object BrowserTaskBridge {
    private var requestHandler: ((String, Boolean) -> Unit)? = null
    private var approvalHandler: ((DevicePlan, Boolean) -> Unit)? = null
    private var planListener: ((DevicePlan, Boolean) -> Unit)? = null
    private var messageListener: ((String) -> Unit)? = null
    private var browserController: ((String) -> Boolean)? = null

    fun registerTaskHandlers(
        requestHandler: (String, Boolean) -> Unit,
        approvalHandler: (DevicePlan, Boolean) -> Unit
    ) {
        this.requestHandler = requestHandler
        this.approvalHandler = approvalHandler
    }

    fun registerBrowserListeners(
        planListener: (DevicePlan, Boolean) -> Unit,
        messageListener: (String) -> Unit
    ) {
        this.planListener = planListener
        this.messageListener = messageListener
    }

    /**
     * Lets the accessibility executor reuse the currently visible in-app browser
     * instead of starting a second Activity for every browser action.
     */
    fun registerBrowserController(controller: (String) -> Boolean) {
        browserController = controller
    }

    fun clearBrowserController() {
        browserController = null
    }

    fun reuseCurrentBrowser(requestedUrl: String): Boolean =
        browserController?.invoke(requestedUrl) == true

    fun isBrowserActive(): Boolean = browserController != null

    fun clearTaskHandlers() {
        requestHandler = null
        approvalHandler = null
    }

    fun clearBrowserListeners() {
        planListener = null
        messageListener = null
    }

    fun submitTask(text: String, append: Boolean) {
        requestHandler?.invoke(text, append)
            ?: messageListener?.invoke("WatchLearn AI task composer is not available.")
    }

    fun publishPlan(plan: DevicePlan, append: Boolean) {
        planListener?.invoke(plan, append)
    }

    fun publishMessage(message: String) {
        messageListener?.invoke(message)
    }

    fun approvePlan(plan: DevicePlan, append: Boolean) {
        approvalHandler?.invoke(plan, append)
            ?: messageListener?.invoke("WatchLearn AI task approval is not available.")
    }
}
