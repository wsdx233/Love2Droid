package top.wsdx233.love2droid

/** Loading spans daemon startup and main-frame navigation, but not about:blank resets. */
internal class DshWebLoadState {
    var isLoading: Boolean = false
        private set
    private var waitingForService = false

    fun waitForService() {
        waitingForService = true
        isLoading = true
    }

    fun start(url: String) {
        if (url == "about:blank") return
        waitingForService = false
        isLoading = true
    }

    fun finish(url: String, currentUrl: String?) {
        if (!waitingForService && url != "about:blank" && url == currentUrl) isLoading = false
    }

    fun stop() {
        waitingForService = false
        isLoading = false
    }
}
