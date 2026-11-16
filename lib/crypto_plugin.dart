/*
 * Copyright (C) 2020. by perol_notsf, All rights reserved
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 *  FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program. If not, see <http://www.gnu.org/licenses/>.
 */

import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:pixez/constants.dart';
import 'package:pixez/er/lprinter.dart';
import 'package:pixez/er/prefer.dart';

//虽然官方有提供包能一键生成，但是少一个依赖更好 :)
class CryptoPlugin {
  static const String _verifierPrefKey = 'oauth_pkce_code_verifier';

  static Future<String> getCodeVer() async {
    const String randomKeySet =
        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    final result = List.generate(128,
            (i) => randomKeySet[Random.secure().nextInt(randomKeySet.length)])
        .join();
    Constants.code_verifier = result;
    try {
      final prefs = await Prefer.getInstance();
      await prefs.setString(_verifierPrefKey, result);
    } catch (_) {}
    return result;
  }

  static Future<String?> loadCodeVer() async {
    final inMemory = Constants.code_verifier;
    if (inMemory != null && inMemory.isNotEmpty) {
      return inMemory;
    }
    try {
      final prefs = await Prefer.getInstance();
      final persisted = prefs.getString(_verifierPrefKey);
      if (persisted != null && persisted.isNotEmpty) {
        Constants.code_verifier = persisted;
        return persisted;
      }
    } catch (_) {}
    return null;
  }

  static Future<String> getCodeChallenge() async {
    final verifier = await loadCodeVer() ?? await getCodeVer();
    LPrinter.d(verifier);
    final codeChallenge = base64Url
        .encode(sha256.convert(ascii.encode(verifier)).bytes)
        .replaceAll('=', '');
    return codeChallenge;
  }
}
