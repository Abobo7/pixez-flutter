/*
 * Copyright (C) 2020. by perol_notsf, All rights reserved
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

import 'dart:async';
import 'dart:convert';
import 'dart:core';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:device_info_plus/device_info_plus.dart';
import 'package:dio/dio.dart';
import 'package:dio_compatibility_layer/dio_compatibility_layer.dart';
import 'package:flutter/foundation.dart';
import 'package:intl/intl.dart';
import 'package:pixez/crypto_plugin.dart';
import 'package:pixez/er/hoster.dart';
import 'package:pixez/er/prefer.dart';
import 'package:pixez/main.dart';
import 'package:pixez/network/network_mode.dart';
import 'package:pixez/network/pixez_network_settings.dart';
import 'package:rhttp/rhttp.dart' as r;

final OAuthClient oAuthClient = OAuthClient();

class OAuthClient {
  final String hashSalt =
      "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c";

  late Dio httpClient;
  bool _adapterInitialized = false;
  Future<Dio>? _creatingClientFuture;

  static const BASE_OAUTH_URL_HOST = "oauth.secure.pixiv.net";

  final String CLIENT_ID = "MOBrBDS8blbauoSck0ZfDbtuzpyT";
  final String CLIENT_SECRET = "lsACyCD94FhDUtGTXi3QzcFE2uU1hqtDaKeqrdwj";
  final String REFRESH_CLIENT_ID = "KzEZED7aC0vird8jWyHM38mXjNTY";
  final String REFRESH_CLIENT_SECRET =
      "W9JZoJe00qPvJsiyCGT3CCtC6ZUtdpKpzMbNlUGP"; //这换行绝了

  String getIsoDate() {
    DateTime dateTime = DateTime.now().toUtc();
    DateFormat dateFormat = DateFormat("yyyy-MM-dd'T'HH:mm:ss'+00:00'");
    return dateFormat.format(dateTime);
  }

  static final String AUTHORIZATION = "Authorization";

  void _refreshClientSignatureHeaders(Map<String, dynamic> headers) {
    final String time = getIsoDate();
    headers["X-Client-Time"] = time;
    headers["X-Client-Hash"] = getHash(time + hashSalt);
  }

  Future<Dio> createDioClient() {
    final inflight = _creatingClientFuture;
    if (inflight != null) return inflight;
    final future = _createDioClientInternal();
    _creatingClientFuture = future;
    future.whenComplete(() {
      if (identical(_creatingClientFuture, future)) {
        _creatingClientFuture = null;
      }
    });
    return future;
  }

  Future<Dio> _createDioClientInternal() async {
    final compatibleClient = await r.RhttpCompatibleClient.create(
      settings: PixezNetworkSettings.forHost(
        BASE_OAUTH_URL_HOST,
        userSetting.oauthNetworkMode,
      ),
    );
    httpClient.httpClientAdapter = ConversionLayerAdapter(compatibleClient);
    _adapterInitialized = true;
    if (Platform.isAndroid) {
      try {
        DeviceInfoPlugin deviceInfo = DeviceInfoPlugin();
        AndroidDeviceInfo androidInfo = await deviceInfo.androidInfo;
        var headers = httpClient.options.headers;
        headers['User-Agent'] =
            "PixivAndroidApp/5.0.166 (Android ${androidInfo.version.release}; ${androidInfo.model})";
        headers['App-OS-Version'] = "Android ${androidInfo.version.release}";
      } catch (e) {}
    }
    return httpClient;
  }

  Future<void> _ensureReady() async {
    try {
      await Prefer.getInstance();
    } catch (_) {}
    if (!_adapterInitialized) {
      await createDioClient();
    }
    httpClient.options.baseUrl = 'https://$BASE_OAUTH_URL_HOST';
    _refreshClientSignatureHeaders(httpClient.options.headers);
  }

  Future<Response> _postFormWithFallback(
    String path,
    Map<String, dynamic> data,
  ) async {
    await _ensureReady();
    if (Platform.isAndroid) {
      // Allow MIUI PowerKeeper / firewall rules to unblock app UID on browser -> app resume
      await Future.delayed(const Duration(milliseconds: 250));
      _refreshClientSignatureHeaders(httpClient.options.headers);
    }
    try {
      return await httpClient
          .post(
            path,
            data: data,
            options: Options(contentType: Headers.formUrlEncodedContentType),
          )
          .timeout(const Duration(seconds: 6));
    } catch (e) {
      // HTTP status responses (e.g. 400 invalid_request) reached Pixiv; do not replay single-use codes
      if (e is DioException && e.response != null) rethrow;
      if (e is! DioException && e is! TimeoutException) rethrow;

      if (userSetting.oauthNetworkMode != NetworkMode.standard) {
        // Fallback to standard TLS 1.3 (via system VPN / proxy) when ECH bootstrap stalls or is unreachable
        try {
          final fallbackHeaders = Map<String, dynamic>.from(
            httpClient.options.headers,
          );
          _refreshClientSignatureHeaders(fallbackHeaders);
          final fallbackDio = Dio(
            BaseOptions(
              baseUrl: 'https://$BASE_OAUTH_URL_HOST',
              headers: fallbackHeaders,
              contentType: Headers.formUrlEncodedContentType,
            ),
          );
          final stdClient = await r.RhttpCompatibleClient.create(
            settings: r.ClientSettings(
              timeoutSettings: const r.TimeoutSettings(
                connectTimeout: Duration(seconds: 6),
                timeout: Duration(seconds: 12),
              ),
              tlsSettings: const r.TlsSettings(
                verifyCertificates: true,
                rootCertSource: r.RootCertSource.webpki,
                sni: true,
              ),
              dnsSettings: r.DnsSettings.static(
                overrides: {
                  BASE_OAUTH_URL_HOST: [
                    Hoster.oauth(),
                    '210.140.139.155',
                    '104.18.10.118',
                  ],
                },
              ),
            ),
          );
          fallbackDio.httpClientAdapter = ConversionLayerAdapter(stdClient);
          return await fallbackDio
              .post(
                path,
                data: data,
                options: Options(
                  contentType: Headers.formUrlEncodedContentType,
                ),
              )
              .timeout(const Duration(seconds: 10));
        } catch (fallbackErr) {
          if (fallbackErr is DioException && fallbackErr.response != null) {
            rethrow;
          }
        }
      }

      // Final retry on primary client
      await Future.delayed(const Duration(milliseconds: 400));
      _refreshClientSignatureHeaders(httpClient.options.headers);
      return await httpClient
          .post(
            path,
            data: data,
            options: Options(contentType: Headers.formUrlEncodedContentType),
          )
          .timeout(const Duration(seconds: 10));
    }
  }

  OAuthClient() {
    String time = getIsoDate();
    httpClient = Dio(
      BaseOptions(
        baseUrl: 'https://${BASE_OAUTH_URL_HOST}',
        headers: {
          "X-Client-Time": time,
          "X-Client-Hash": getHash(time + hashSalt),
          "User-Agent": "PixivAndroidApp/5.0.155 (Android 6.0; Pixel C)",
          HttpHeaders.acceptLanguageHeader: "zh-CN",
          "App-OS": "Android",
          "App-OS-Version": "Android 6.0",
          "App-Version": "5.0.166",
        },
        contentType: Headers.formUrlEncodedContentType,
      ),
    );
    if (kDebugMode) {
      httpClient.interceptors.add(
        LogInterceptor(
          responseBody: true,
          responseHeader: true,
          requestBody: true,
        ),
      );
    }
  }

  static String getHash(String string) {
    var content = Utf8Encoder().convert(string);
    var digest = md5.convert(content);
    return digest.toString();
  }

  Future<Response> postAuthToken(
    String userName,
    String passWord, {
    String deviceToken = "pixiv",
  }) {
    return _postFormWithFallback(
      "/auth/token",
      {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "password",
        "username": userName,
        "password": passWord,
        "Device_token": deviceToken,
        "get_secure_url": true,
        "include_policy": true,
      },
    );
  }

  Future<Response> code2Token(String code) async {
    final verifier = await CryptoPlugin.loadCodeVer();
    if (verifier == null || verifier.isEmpty) {
      throw StateError('未找到登录校验码 (code_verifier)，请点击“登录”按钮重新发起浏览器授权');
    }
    return _postFormWithFallback(
      "/auth/token",
      {
        "code": code,
        "redirect_uri":
            "https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback",
        "grant_type": "authorization_code",
        "include_policy": true,
        "client_id": CLIENT_ID,
        "code_verifier": verifier,
        "client_secret": CLIENT_SECRET,
      },
    );
  }

  static Future<String> generateWebviewUrl({bool create = false}) async {
    await generateCodeVerify();
    String codeChallenge = await CryptoPlugin.getCodeChallenge();
    String url = !create
        ? "https://app-api.pixiv.net/web/v1/login?code_challenge=${codeChallenge}&code_challenge_method=S256&client=pixiv-android"
        : "https://app-api.pixiv.net/web/v1/provisional-accounts/create?code_challenge=${codeChallenge}&code_challenge_method=S256&client=pixiv-android";
    return url;
  }

  static Future<String> generateCodeVerify() async {
    return await CryptoPlugin.getCodeVer();
  }

  Future<Response> postRefreshAuthToken({
    refreshToken = String,
    deviceToken = String,
  }) {
    return _postFormWithFallback(
      "/auth/token",
      {
        "client_id": CLIENT_ID,
        "client_secret": CLIENT_SECRET,
        "grant_type": "refresh_token",
        "refresh_token": refreshToken,
        "include_policy": true,
      },
    );
  }
}
