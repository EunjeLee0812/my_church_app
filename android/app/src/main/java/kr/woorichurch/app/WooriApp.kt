package kr.woorichurch.app

import android.app.Application
import kr.woorichurch.app.data.Api
import kr.woorichurch.app.data.Prefs
import kr.woorichurch.app.data.Repo

class WooriApp : Application() {
    lateinit var prefs: Prefs; private set
    lateinit var repo: Repo; private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = Repo(Api(prefs), prefs)
    }
}
