import 'dart:async';

import 'package:autox_api/autox_api.dart'
    show ConversationDetail, ConversationSummary, ListingsApi, MessagesApi, SendMessageRequest;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/api/api_failure.dart';
import '../../core/api/api_providers.dart';
import '../../core/auth/session.dart';

final messagesApiProvider = Provider<MessagesApi>((ref) => ref.watch(autoxApiProvider).getMessagesApi());

/// Reporting a listing, and whether one has been: on the listings API.
final listingsApiProvider = Provider<ListingsApi>((ref) => ref.watch(autoxApiProvider).getListingsApi());

/// Conversations asked for per page of the inbox, as on the web.
const inboxPageSize = 20;

/// How often an open conversation is asked for again, while it is on screen and the app in the
/// foreground. There is no push yet: this is how an answer shows up without leaving the screen.
const conversationPollInterval = Duration(seconds: 15);

/// How many conversations have a message the signed-in person has not opened: the Messages tab's
/// badge. Asked for when the session starts, when the app comes back to the foreground, when the
/// tab is selected, and after anything that changes it (opening a conversation, sending). A
/// failure keeps the last count: the badge is a hint, and the next occasion asks again.
class UnreadCount extends Notifier<int> {
  @override
  int build() {
    ref.listen(sessionProvider, (previous, next) {
      if (next is SignedIn && previous is! SignedIn) {
        unawaited(refresh());
      } else if (next is! SignedIn && previous != null) {
        state = 0;
      }
    }, fireImmediately: true);
    return 0;
  }

  Future<void> refresh() async {
    if (ref.read(sessionProvider) is! SignedIn) {
      return;
    }
    try {
      final reply = await ref.read(messagesApiProvider).unread();
      if (ref.mounted && ref.read(sessionProvider) is SignedIn) {
        state = reply.data?.conversations ?? 0;
      }
    } on Object {
      // Kept as it was.
    }
  }
}

final unreadCountProvider = NotifierProvider<UnreadCount, int>(UnreadCount.new);

/// The inbox as far as it has been read: conversations as buyer and as seller, newest first.
class InboxState {
  const InboxState({
    this.items = const [],
    this.hasNext = false,
    this.loading = true,
    this.loadingMore = false,
    this.failure,
    this.readHere = const {},
  });

  final List<ConversationSummary> items;
  final bool hasNext;

  /// The first page is being read (and nothing is shown yet).
  final bool loading;
  final bool loadingMore;
  final ApiFailure? failure;

  /// Conversations opened since the list was read: nothing in them is unread any more.
  final Set<String> readHere;

  int unreadOf(ConversationSummary item) => readHere.contains(item.id) ? 0 : (item.unreadCount ?? 0);

  InboxState copy({
    List<ConversationSummary>? items,
    bool? hasNext,
    bool? loading,
    bool? loadingMore,
    ApiFailure? failure,
    bool clearFailure = false,
    Set<String>? readHere,
  }) => InboxState(
    items: items ?? this.items,
    hasNext: hasNext ?? this.hasNext,
    loading: loading ?? this.loading,
    loadingMore: loadingMore ?? this.loadingMore,
    failure: clearFailure ? null : (failure ?? this.failure),
    readHere: readHere ?? this.readHere,
  );
}

class Inbox extends Notifier<InboxState> {
  int _pages = 0;

  @override
  InboxState build() {
    ref.listen(sessionProvider, (previous, next) {
      if (next is! SignedIn && previous is SignedIn) {
        _pages = 0;
        state = const InboxState();
      } else if (next is SignedIn && previous is! SignedIn) {
        unawaited(refresh());
      }
    });
    unawaited(refresh());
    return const InboxState();
  }

  /// The first page again (pull to refresh, the tab selected, a message sent).
  Future<void> refresh() async {
    if (ref.read(sessionProvider) is! SignedIn) {
      return;
    }
    try {
      final reply = await ref.read(messagesApiProvider).inbox(page: 0, size: inboxPageSize);
      _pages = 1;
      if (ref.mounted) {
        state = InboxState(items: reply.data?.items ?? const [], hasNext: reply.data?.hasNext ?? false, loading: false);
      }
    } on Object catch (error) {
      if (ref.mounted) {
        state = state.copy(loading: false, failure: ApiFailure.from(error));
      }
    }
  }

  /// The next page, appended, when the list is scrolled to its end.
  Future<void> loadMore() async {
    if (!state.hasNext || state.loadingMore || state.loading) {
      return;
    }
    state = state.copy(loadingMore: true);
    try {
      final reply = await ref.read(messagesApiProvider).inbox(page: _pages, size: inboxPageSize);
      _pages++;
      final known = {for (final item in state.items) item.id};
      if (ref.mounted) {
        state = state.copy(
          items: [...state.items, ...?reply.data?.items?.where((item) => !known.contains(item.id))],
          hasNext: reply.data?.hasNext ?? false,
          loadingMore: false,
        );
      }
    } on Object {
      if (ref.mounted) {
        state = state.copy(loadingMore: false);
      }
    }
  }

  void markReadHere(String id) => state = state.copy(readHere: {...state.readHere, id});
}

final inboxProvider = NotifierProvider<Inbox, InboxState>(Inbox.new);

/// One conversation on screen.
class ConversationState {
  const ConversationState({this.detail, this.loading = true, this.failure, this.reportedHere = const {}});

  final ConversationDetail? detail;
  final bool loading;
  final ApiFailure? failure;

  /// Messages reported from this screen: shown as "Signalé" without reading the conversation again.
  final Set<String> reportedHere;
}

/// A conversation: read when opened and marked read, read again quietly by the screen's poll,
/// and answered in. Sending is never retried here: the API takes no idempotency key, so a send
/// the app repeated on its own could arrive twice. A failed send is the person's to try again.
class Conversation extends Notifier<ConversationState> {
  Conversation(this.id);

  final String id;

  MessagesApi get _api => ref.read(messagesApiProvider);

  @override
  ConversationState build() {
    // Read once signed in: at launch the stored session is still being checked, and a request
    // sent before it would go without a token. After build: load reads the state it replaces.
    ref.listen(sessionProvider, (previous, next) {
      if (next is SignedIn && previous is! SignedIn) {
        unawaited(Future.microtask(load));
      }
    }, fireImmediately: true);
    return const ConversationState();
  }

  /// Reads it; [quietly] (the poll) keeps what is shown when the read fails.
  Future<void> load({bool quietly = false}) async {
    final before = state.detail?.messages?.lastOrNull?.id;
    try {
      final reply = await _api.conversation(id: id);
      if (!ref.mounted) {
        return;
      }
      state = ConversationState(detail: reply.data, loading: false, reportedHere: state.reportedHere);
      final after = reply.data?.messages?.lastOrNull?.id;
      if (!quietly || after != before) {
        unawaited(_markRead());
      }
    } on Object catch (error) {
      if (ref.mounted && !quietly) {
        state = ConversationState(loading: false, failure: ApiFailure.from(error), reportedHere: state.reportedHere);
      }
    }
  }

  Future<void> _markRead() async {
    try {
      await _api.markRead(id: id);
      if (ref.mounted) {
        ref.read(inboxProvider.notifier).markReadHere(id);
        unawaited(ref.read(unreadCountProvider.notifier).refresh());
      }
    } on Object {
      // Marked read next time it is opened.
    }
  }

  /// Sends [body] once. Throws an [ApiFailure] when it was not sent.
  Future<void> send(String body) async {
    try {
      final reply = await _api.reply(
        id: id,
        sendMessageRequest: SendMessageRequest(body: body.trim()),
      );
      if (ref.mounted) {
        state = ConversationState(detail: reply.data, loading: false, reportedHere: state.reportedHere);
        unawaited(ref.read(inboxProvider.notifier).refresh());
      }
    } on Object catch (error) {
      throw ApiFailure.from(error);
    }
  }

  void markReported(String messageId) => state = ConversationState(
    detail: state.detail,
    loading: state.loading,
    failure: state.failure,
    reportedHere: {...state.reportedHere, messageId},
  );
}

final conversationProvider = NotifierProvider.autoDispose.family<Conversation, ConversationState, String>(
  Conversation.new,
);
