// File: lib/utils/json_utils.dart

import 'dart:convert';

class JsonUtils {
  static String encodeMessage(Map<String, dynamic> message) {
    return jsonEncode(message);
  }
  
  static Map<String, dynamic>? decodeMessage(String data) {
    try {
      return jsonDecode(data);
    } catch (e) {
      return null;
    }
  }
}