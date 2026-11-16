import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';

import 'fake_auth.dart';
import 'fake_http.dart';
import 'listing_detail.dart';

/// The messaging and report endpoints, faked over [FakeAccountApi]'s accounts: the same "latest
/// access token only" rule, a conversation store, and answers a test can change or hold.
///
/// The person signed in is the buyer, "Jean Dupont" (`u1`); the seller is "Garage Bellevue"
/// (`s1`), selling listing `l1`.
class FakeMessaging {
  FakeMessaging(this.accounts);

  final FakeAccountApi accounts;

  /// The Messages tab's badge.
  int unread = 0;

  /// Conversations by id, as `GET /conversations/{id}` sends them.
  final Map<String, Map<String, Object?>> conversations = {};

  /// The buyer's conversation about each listing: writing about it again continues it.
  final Map<String, String> conversationOfListing = {};

  /// Every message the server accepted, in order: what "sent exactly once" is checked on.
  final List<String> accepted = [];

  /// Report bodies, by path.
  final Map<String, List<Map<String, Object?>>> reports = {};

  /// Whether the buyer has already reported listing `l1`.
  bool listingReported = false;

  /// Replaces an endpoint's answer (by path).
  final Map<String, FakeResponse> answers = {};

  /// Holds an endpoint's answer until released (by path).
  final Map<String, Completer<void>> gates = {};

  int _messages = 0;

  static String _now() => DateTime.now().toUtc().toIso8601String();

  Map<String, Object?> message(String body, {required bool mine, String? id, bool reportedByMe = false}) => {
    'id': id ?? 'm${++_messages}',
    'sentByMe': mine,
    'body': body,
    'hidden': false,
    'erased': false,
    'reportedByMe': reportedByMe,
    'sentAt': _now(),
  };

  /// A conversation about `l1`, with [messages] (the seller's question by default), stored.
  Map<String, Object?> conversation({
    String id = 'c1',
    String role = 'BUYER',
    bool canReply = true,
    List<Map<String, Object?>>? messages,
  }) {
    final detail = <String, Object?>{
      'id': id,
      'role': role,
      'listing': {
        'id': 'l1',
        'title': 'Peugeot 3008 1.5 BlueHDi 130 Allure',
        'priceAmount': 2249000,
        'currencyCode': 'EUR',
        'online': true,
      },
      'counterpart': {'id': 's1', 'displayName': 'Garage Bellevue', 'role': 'PROFESSIONNEL'},
      'canReply': canReply,
      'messages': messages ?? [message('Bonjour, toujours intéressé ?', mine: false, id: 'm-seller')],
      'earlierOmitted': false,
    };
    conversations[id] = detail;
    conversationOfListing['l1'] = id;
    return detail;
  }

  Map<String, Object?> _summary(Map<String, Object?> detail) {
    final messages = (detail['messages']! as List).cast<Map<String, Object?>>();
    final last = messages.isEmpty ? null : messages.last;
    return {
      'id': detail['id'],
      'role': detail['role'],
      'listing': detail['listing'],
      'counterpart': detail['counterpart'],
      'unreadCount': last != null && last['sentByMe'] == false ? 1 : 0,
      'canReply': detail['canReply'],
      'lastMessageAt': last?['sentAt'],
      'lastMessage': last == null
          ? null
          : {'preview': last['body'], 'sentByMe': last['sentByMe'], 'hidden': false, 'erased': false},
    };
  }

  bool _signedIn(RequestOptions request) => request.headers['Authorization'] == 'Bearer ${accounts.validAccess}';

  static const _unauthorised = FakeResponse.json(401, {'status': 401, 'code': 'INVALID_TOKEN'});

  FutureOr<FakeResponse> Function(RequestOptions) _endpoint(
    String path,
    FakeResponse Function(RequestOptions request, Map<String, Object?> body) answer,
  ) => (request) async {
    final data = request.data;
    final body = data is String && data.isNotEmpty ? jsonDecode(data) as Map<String, Object?> : <String, Object?>{};
    await gates[path]?.future;
    if (!_signedIn(request)) {
      return _unauthorised;
    }
    return answers[path] ?? answer(request, body);
  };

  /// Writes [body] in the conversation [id] (the buyer's), and answers with it.
  FakeResponse _write(String id, Map<String, Object?> body) {
    final detail = conversations[id]!;
    final text = body['body']! as String;
    accepted.add(text);
    (detail['messages']! as List).add(message(text, mine: true));
    return FakeResponse.json(200, detail);
  }

  /// The app's whole API: the accounts, the listing `l1` and its seller's page, and messaging.
  FakeHttp http() {
    final http = accounts.http();
    http.routes.addAll({
      '/api/v1/listings/l1': FakeResponse.json(200, detailJson()),
      '/api/v1/listings/l1/similar': const FakeResponse.json(200, <Object>[]),
    });
    http.handlers.addAll({
      '/api/v1/conversations/unread': _endpoint(
        '/api/v1/conversations/unread',
        (_, _) => FakeResponse.json(200, {'conversations': unread}),
      ),
      '/api/v1/conversations': _endpoint(
        '/api/v1/conversations',
        (_, _) => FakeResponse.json(200, {
          'items': [for (final detail in conversations.values.toList().reversed) _summary(detail)],
          'page': 0,
          'size': 20,
          'totalElements': conversations.length,
          'totalPages': 1,
          'hasNext': false,
        }),
      ),
      '/api/v1/listings/l1/conversation': _endpoint('/api/v1/listings/l1/conversation', (_, body) {
        final existing = conversationOfListing['l1'];
        if (existing != null) {
          return _write(existing, body);
        }
        conversation(id: 'c-new', messages: []);
        return _write('c-new', body);
      }),
      '/api/v1/listings/l1/report': _endpoint('/api/v1/listings/l1/report', (request, body) {
        if (request.method == 'GET') {
          return FakeResponse.json(200, {'reported': listingReported});
        }
        (reports['/api/v1/listings/l1/report'] ??= []).add(body);
        if (listingReported) {
          return const FakeResponse.json(409, {'status': 409, 'code': 'LISTING_ALREADY_REPORTED'});
        }
        listingReported = true;
        return const FakeResponse.json(204, null);
      }),
    });
    for (final id in ['c1', 'c-new']) {
      http.handlers.addAll({
        '/api/v1/conversations/$id': _endpoint(
          '/api/v1/conversations/$id',
          (_, _) => conversations[id] == null
              ? const FakeResponse.json(404, {'status': 404, 'code': 'NOT_FOUND'})
              : FakeResponse.json(200, conversations[id]),
        ),
        '/api/v1/conversations/$id/read': _endpoint(
          '/api/v1/conversations/$id/read',
          (_, _) => const FakeResponse.json(204, null),
        ),
        '/api/v1/conversations/$id/messages': _endpoint(
          '/api/v1/conversations/$id/messages',
          (_, body) => _write(id, body),
        ),
      });
    }
    http.handlers['/api/v1/messages/m-seller/report'] = _endpoint('/api/v1/messages/m-seller/report', (_, body) {
      (reports['/api/v1/messages/m-seller/report'] ??= []).add(body);
      return const FakeResponse.json(204, null);
    });
    return http;
  }
}
