package com.ridi.oauth2

import android.util.Base64
import com.ridi.books.helper.io.loadObject
import io.reactivex.Observable
import io.reactivex.ObservableEmitter
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.util.Calendar

data class JWT(var subject: String, var userIndex: Int?, var expiresAt: Int)

class UnexpectedRedirectUriException(override var message: String) : RuntimeException(message)

class ResponseCodeException(override var message: String) : RuntimeException(message)

class TokenManager {
    companion object {
        private const val DEV_HOST = "account.dev.ridi.io/"
        private const val REAL_HOST = "account.ridibooks.com/"
        internal var BASE_URL = "https://$REAL_HOST"

        internal const val COOKIE_KEY_RIDI_AT = "ridi-at"
        internal const val COOKIE_KEY_RIDI_RT = "ridi-rt"

        internal fun JSONObject.parseCookie(cookieString: String) {
            val cookie = cookieString.split("=", ";")
            val cookieKey = cookie[0]
            val cookieValue = cookie[1]
            if (cookieKey == COOKIE_KEY_RIDI_AT || cookieKey == COOKIE_KEY_RIDI_RT) {
                put(cookieKey, cookieValue)
            }
        }
    }

    private var apiManager = ApiManager()

    var clientId: String? = null
        set(value) {
            field = value
            clearTokens()
        }

    var tokenFile: File? = null
        set(value) {
            field = value
            clearTokens()
            apiManager.cookieStorage.tokenFile = value
        }

    var useDevMode: Boolean = false
        set(value) {
            field = value
            clearTokens()
            BASE_URL = "https://" + if (value) DEV_HOST else REAL_HOST
        }

    var tokenEncryptionKey: String? = null
        set(value) {
            field = value
            apiManager.cookieStorage.tokenEncryptionKey = value
        }

    var sessionId: String = ""
        set(value) {
            field = value
            clearTokens()
        }

    private fun clearTokens() {
        rawAccessToken = null
        refreshToken = null
        parsedAccessToken = null
    }

    private fun getSavedJSON(): JSONObject {
        val savedToken = tokenFile!!.loadObject<String>() ?: throw FileNotFoundException()
        return JSONObject(savedToken.decodeWithAES256(tokenEncryptionKey))
    }

    private var rawAccessToken: String? = null
        get() {
            if (field == null) {
                field = getSavedJSON().getString(COOKIE_KEY_RIDI_AT)
            }
            return field
        }

    private var refreshToken: String? = null
        get() {
            if (field == null) {
                field = getSavedJSON().getString(COOKIE_KEY_RIDI_RT)
            }
            return field
        }

    private var parsedAccessToken: JWT? = null
        get() {
            if (field == null) {
                field = parseAccessToken()
            }
            return field
        }

    private fun parseAccessToken(): JWT {
        val splitString = rawAccessToken!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        // splitString[0]에는 필요한 정보가 없다.
        val jsonObject = JSONObject(String(Base64.decode(splitString[1], Base64.DEFAULT)))
        return JWT(jsonObject.getString("sub"),
            jsonObject.getInt("u_idx"),
            jsonObject.getInt("exp"))
    }

    private fun isAccessTokenExpired() =
        parsedAccessToken!!.expiresAt < Calendar.getInstance().timeInMillis / 1000

    fun getAccessToken(redirectUri: String): Observable<JWT> {
        return Observable.create { emitter ->
            if (tokenFile == null || clientId == null) {
                emitter.onError(IllegalStateException())
            } else if (tokenFile!!.exists().not()) {
                requestAuthorization(emitter, redirectUri)
            } else {
                if (isAccessTokenExpired()) {
                    refreshAccessToken(emitter)
                } else {
                    emitter.onNext(parsedAccessToken!!)
                    emitter.onComplete()
                }
            }
        }
    }

    private fun requestAuthorization(emitter: ObservableEmitter<JWT>, redirectUri: String) {
        val sessionCookie = "PHPSESSID=$sessionId;"
        apiManager.service!!.requestAuthorization(sessionCookie, clientId!!, "code", redirectUri)
            .enqueue(object : Callback<ResponseBody> {
                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    emitter.onError(t)
                }

                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.code() == HttpURLConnection.HTTP_MOVED_TEMP) {
                        val redirectLocation = response.headers().values("Location")[0]
                        if (redirectLocation == redirectUri) {
                            // 토큰은 이미 ApiManager 내의 CookieInterceptor에서 tokenFile에 저장된 상태이다.
                            emitter.onNext(parsedAccessToken!!)
                            emitter.onComplete()
                        } else {
                            emitter.onError(UnexpectedRedirectUriException(redirectLocation))
                        }
                    } else {
                        emitter.onError(ResponseCodeException("${response.code()}"))
                    }
                }
            })
    }

    private fun refreshAccessToken(emitter: ObservableEmitter<JWT>) {
        apiManager.service!!.refreshAccessToken(rawAccessToken!!, refreshToken!!)
            .enqueue(object : Callback<ResponseBody> {
                override fun onFailure(call: Call<ResponseBody>, t: Throwable?) {
                    emitter.onError(IllegalStateException(t))
                }

                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    clearTokens()
                    emitter.onNext(parsedAccessToken!!)
                    emitter.onComplete()
                }
            })
    }
}
