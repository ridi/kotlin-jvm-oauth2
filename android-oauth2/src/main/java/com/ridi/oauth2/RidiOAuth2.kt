package com.ridi.oauth2

import android.util.Base64
import com.ridi.books.helper.io.loadObject
import io.reactivex.Observable
import io.reactivex.ObservableOnSubscribe
import io.reactivex.android.schedulers.AndroidSchedulers
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileNotFoundException
import java.net.MalformedURLException
import java.security.InvalidParameterException
import java.util.Calendar

data class JWT(var subject: String, var userIndex: Int?, var expiresAt: Int)

class RidiOAuth2 {
    private var clientId = ""
    private var manager = ApiManager

    companion object {
        private const val DEV_HOST = "account.dev.ridi.io/"
        private const val REAL_HOST = "account.ridibooks.com/"
        internal const val STATUS_CODE_REDIRECT = 302
        internal const val COOKIE_RIDI_AT = "ridi-at"
        internal const val COOKIE_RIDI_RT = "ridi-rt"

        internal var BASE_URL = "https://$REAL_HOST"

        var instance = RidiOAuth2()

        internal lateinit var tokenFile: File
        internal fun JSONObject.parseCookie(cookieString: String) {
            val cookie = cookieString.split("=", ";")
            if (cookie[0] == COOKIE_RIDI_AT || cookie[0] == COOKIE_RIDI_RT) {
                put(cookie[0], cookie[1])
            }
        }
    }

    private var tokenFilePath = ""

    private var refreshToken = ""
    private var rawAccessToken = ""
    private lateinit var parsedAccessToken: JWT

    fun setDevMode() {
        BASE_URL = "https://$DEV_HOST"
    }

    fun setSessionId(sessionId: String) {
        manager.cookies = HashSet()
        manager.cookies.add("PHPSESSID=$sessionId;")
    }

    fun setClientId(clientId: String) {
        this.clientId = clientId
    }

    fun createTokenFileFromPath(path: String) {
        tokenFilePath = path
        tokenFile = File(tokenFilePath)
    }

    private fun readJSONFile() = tokenFile.loadObject<String>() ?: throw FileNotFoundException()

    fun getAccessToken(): String {
        if (rawAccessToken.isEmpty()) {
            val jsonObject = JSONObject(readJSONFile())
            if (jsonObject.has(COOKIE_RIDI_AT)) {
                rawAccessToken = jsonObject.getString(COOKIE_RIDI_AT)
            }
        }
        parsedAccessToken = parseAccessToken()
        return rawAccessToken
    }

    fun parseAccessToken(): JWT {
        val splitString = rawAccessToken.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        // splitString[0]에는 필요한 정보가 없다.
        val jsonObject = JSONObject(String(Base64.decode(splitString[1], Base64.DEFAULT)))
        return JWT(jsonObject.getString("sub"),
            jsonObject.getInt("u_idx"),
            jsonObject.getInt("exp"))
    }

    fun getRefreshToken(): String {
        if (refreshToken.isEmpty()) {
            val jsonObject = JSONObject(readJSONFile())
            if (jsonObject.has(COOKIE_RIDI_RT)) {
                refreshToken = jsonObject.getString(COOKIE_RIDI_RT)
            }
        }
        return refreshToken
    }

    private fun isAccessTokenExpired(): Boolean {
        getAccessToken()
        return parsedAccessToken.expiresAt < Calendar.getInstance().timeInMillis / 1000
    }

    fun getOAuthToken(redirectUri: String): Observable<JWT> {
        if (tokenFilePath.isEmpty()) {
            return Observable.create(ObservableOnSubscribe<JWT> { emitter ->
                emitter.onError(FileNotFoundException())
                emitter.onComplete()
            }).subscribeOn(AndroidSchedulers.mainThread())
        }
        if (tokenFile.exists().not()) {
            return if (clientId.isEmpty()) {
                Observable.create(ObservableOnSubscribe<JWT> { emitter ->
                    emitter.onError(IllegalStateException())
                    emitter.onComplete()
                }).subscribeOn(AndroidSchedulers.mainThread())
            } else {
                Observable.create(ObservableOnSubscribe<JWT> { emitter ->
                    manager.create().requestAuthorization(clientId, "code", redirectUri)
                        .enqueue(object : Callback<ResponseBody> {
                            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                                emitter.onError(t)
                                emitter.onComplete()
                            }

                            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                                if (response.code() == STATUS_CODE_REDIRECT) {
                                    val redirectLocation = response.headers().values("Location")[0]
                                    if (redirectLocation == redirectUri) {
                                        // 토큰은 이미 ApiManager 내의 CookieInterceptor에서 tokenFile에 저장된 상태이다.
                                        getAccessToken()
                                        emitter.onNext(parsedAccessToken)
                                    } else {
                                        emitter.onError(MalformedURLException())
                                    }
                                } else {
                                    emitter.onError(InvalidParameterException("${response.code()}"))
                                }
                                emitter.onComplete()
                            }
                        })
                }).subscribeOn(AndroidSchedulers.mainThread())
            }
        } else {
            return if (isAccessTokenExpired()) {
                Observable.create(ObservableOnSubscribe<JWT> { emitter ->
                    manager.create().refreshAccessToken(getAccessToken(), getRefreshToken())
                        .enqueue(object : Callback<ResponseBody> {
                            override fun onFailure(call: Call<ResponseBody>, t: Throwable?) {
                                emitter.onError(IllegalStateException())
                                emitter.onComplete()
                            }

                            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                                emitter.onNext(parsedAccessToken)
                                emitter.onComplete()
                            }
                        })
                }).subscribeOn(AndroidSchedulers.mainThread())
            } else {
                Observable.just(parsedAccessToken)
                    .subscribeOn(AndroidSchedulers.mainThread())
            }
        }
    }
}
