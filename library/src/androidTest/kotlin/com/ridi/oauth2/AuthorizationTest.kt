package com.ridi.oauth2

import com.auth0.android.jwt.JWT
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.util.Date

class AuthorizationTest {
    companion object {
        private const val VALID_SESSION_ID = "1"
        private const val INVALID_SESSION_ID = "2"
        private const val CLIENT_ID = "3"
        private const val LOGIN_PAGE = "login?return_url=login_required"
        private const val RIDI_AT = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJBbmRyb2lkS2ltIiwidV9pZHg" +
            "iOjI2Mjc5MjUsImV4cCI6MTUzMDc2MTcwNywiY2xpZW50X2lkIjoiTmt0MlhkYzB6TXVXbXllNk1Ta1lncUNoOXE2SmplTUN" +
            "zVWlIMWtnTCIsInNjb3BlIjoiYWxsIn0.KP_jrSc1KZ36-TYf-oiTyMl2Zn-dm9C8x-eY0bV0uQ8"
        private const val RIDI_AT_EXPIRES_AT_ZERO = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJBbmRyb2l" +
            "kS2ltIiwidV9pZHgiOjI2Mjc5MjUsImV4cCI6MCwiY2xpZW50X2lkIjoiTmt0MlhkYzB6TXVXbXllNk1Ta1lncUNoOXE2Smp" +
            "lTUNzVWlIMWtnTCIsInNjb3BlIjoiYWxsIn0.YVxEdViJVf450hLgrscUSFfmO-x-sl9MXaODMz5afbU"
        private const val RIDI_RT = "NHiVQz0ECBzlyI1asqsK6pfp32zvLD"
    }

    private lateinit var mockWebServer: MockWebServer
    private lateinit var apiBaseUrl: String
    private lateinit var authorization: Authorization

    @Before
    fun setUp() {
        mockWebServer = MockWebServer().apply {
            start()
            setDispatcher(object : Dispatcher() {
                @Throws(InterruptedException::class)
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val url = request.requestUrl.toString()
                    val atCookie = "${Authorization.COOKIE_NAME_RIDI_AT}=$RIDI_AT_EXPIRES_AT_ZERO;"
                    val rtCookie = "${Authorization.COOKIE_NAME_RIDI_RT}=$RIDI_RT;"

                    return if (url.contains("ridi/authorize")) {
                        MockResponse().setResponseCode(HttpURLConnection.HTTP_MOVED_TEMP).run {
                            if (request.headers.values("Cookie")
                                    .any { it.contains("PHPSESSID=$INVALID_SESSION_ID;") }) {
                                setHeader("Location", LOGIN_PAGE)
                            } else {
                                setHeader("Location", Authorization.AUTHORIZATION_REDIRECT_URI)
                                addHeader("Set-Cookie", atCookie)
                                addHeader("Set-Cookie", rtCookie)
                            }
                        }
                    } else if (url.contains(LOGIN_PAGE) || url.contains(Authorization.AUTHORIZATION_REDIRECT_URI)) {
                        MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                    } else if (url.contains("ridi/token")) {
                        MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                            .addHeader("Set-Cookie", atCookie)
                            .addHeader("Set-Cookie", rtCookie)
                    } else {
                        MockResponse().setResponseCode(HttpURLConnection.HTTP_FORBIDDEN)
                    }
                }
            })
        }
        apiBaseUrl = mockWebServer.url("/").toString()
        authorization = Authorization(apiBaseUrl, CLIENT_ID)
    }

    @Test
    fun testRidiAuthorization() {
        authorization.requestRidiAuthorization(VALID_SESSION_ID).blockingForEach { result ->
            assertEquals(JWT(result.accessToken).subject, "AndroidKim")
        }
    }

    @Test
    fun testTokenRefresh() {
        authorization.refreshAccessToken(RIDI_RT).blockingForEach { result ->
            assertEquals(JWT(result.accessToken).expiresAt, Date(0))
        }
    }

    @Test
    fun testRedirectingToLoginPage() {
        try {
            authorization.requestRidiAuthorization(INVALID_SESSION_ID).blockingSingle()
        } catch (e: UnexpectedResponseException) {
            return
        }
        fail()
    }

    @After
    @Throws
    fun tearDown() {
        mockWebServer.shutdown()
    }
}