import 'dart:async';

import 'package:autox_api/autox_api.dart'
    show ConversationDetail, ConversationRole, MessageReportReason, MessageView, ReportMessageRequest;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/format/numbers.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/pending.dart';
import '../../core/widgets/states.dart';
import '../../core/widgets/surfaces.dart';
import '../account/widgets/form_parts.dart' show FormError;
import '../account/widgets/verify_notice.dart';
import '../listing/widgets/listing_tags.dart' show SellerBadge;
import 'messages_screen.dart' show ListingThumb;
import 'messaging_providers.dart';
import 'messaging_text.dart';
import 'widgets/report_sheet.dart';

/// One conversation, `/messages/:id`: who and about what, the messages oldest to newest with the
/// newest at the bottom, and the box to answer in. Opening it marks it read.
///
/// While it is on screen and the app in the foreground, it is read again every
/// [conversationPollInterval]; it stops in the background, behind another screen, on another tab,
/// and when left.
class ConversationScreen extends ConsumerStatefulWidget {
  const ConversationScreen({super.key, required this.id});

  final String id;

  @override
  ConsumerState<ConversationScreen> createState() => _ConversationScreenState();
}

class _ConversationScreenState extends ConsumerState<ConversationScreen> {
  late final AppLifecycleListener _lifecycle;
  bool _foreground = true;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _foreground = switch (WidgetsBinding.instance.lifecycleState) {
      null || AppLifecycleState.resumed => true,
      _ => false,
    };
    _lifecycle = AppLifecycleListener(
      onResume: () => _setForeground(true),
      onInactive: () => _setForeground(false),
      onHide: () => _setForeground(false),
      onPause: () => _setForeground(false),
    );
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _schedule();
  }

  @override
  void dispose() {
    _poll?.cancel();
    _poll = null;
    _lifecycle.dispose();
    super.dispose();
  }

  void _setForeground(bool foreground) {
    if (_foreground == foreground) {
      return;
    }
    _foreground = foreground;
    _schedule();
    // Back in the foreground: what arrived meanwhile, at once.
    if (foreground && _visible) {
      unawaited(ref.read(conversationProvider(widget.id).notifier).load(quietly: true));
    }
  }

  /// The route on top, and its tab the one shown.
  bool get _visible => (ModalRoute.of(context)?.isCurrent ?? true) && TickerMode.valuesOf(context).enabled;

  void _schedule() {
    final wanted = _foreground && _visible;
    if (wanted && _poll == null) {
      _poll = Timer.periodic(conversationPollInterval, (_) {
        unawaited(ref.read(conversationProvider(widget.id).notifier).load(quietly: true));
      });
    } else if (!wanted && _poll != null) {
      _poll!.cancel();
      _poll = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(conversationProvider(widget.id));
    final detail = state.detail;
    return Scaffold(
      appBar: AppBar(title: Text(detail?.counterpart?.displayName ?? 'Conversation'), centerTitle: false),
      body: switch (state) {
        ConversationState(loading: true) => const LoadingView(label: 'Chargement de la conversation'),
        ConversationState(detail: null, :final failure) => FailureView(
          error: failure ?? const UnexpectedFailure(),
          onRetry: () => ref.read(conversationProvider(widget.id).notifier).load(),
        ),
        ConversationState(detail: final detail?) => _Conversation(id: widget.id, detail: detail, state: state),
      },
    );
  }
}

class _Conversation extends ConsumerWidget {
  const _Conversation({required this.id, required this.detail, required this.state});

  final String id;
  final ConversationDetail detail;
  final ConversationState state;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final mustVerify = ref.watch(mustVerifyProvider);
    final canReply = detail.canReply ?? false;
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s2, AppSpace.gutter, AppSpace.s1),
          child: _Header(detail: detail),
        ),
        Expanded(
          child: _Thread(id: id, detail: detail, reportedHere: state.reportedHere),
        ),
        if (canReply && mustVerify)
          const Padding(
            padding: EdgeInsets.all(AppSpace.gutter),
            child: VerifyEmailNotice(
              'Vérifiez votre adresse e-mail pour répondre : cliquez sur le lien que nous vous avons envoyé.',
            ),
          )
        else if (canReply)
          Composer(id: id, role: detail.role)
        else
          Padding(
            padding: const EdgeInsets.all(AppSpace.gutter),
            child: Semantics(
              container: true,
              child: Text(
                'Cette conversation est fermée : l’annonce a été retirée, ou l’un des comptes n’est plus actif. '
                'Vous pouvez toujours relire les messages.',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ),
          ),
      ],
    );
  }
}

/// Who, and about which listing (or which dealer page), as a card over the thread: the listing's
/// photo, its title and price; the whole card opens the listing while it is online.
class _Header extends StatelessWidget {
  const _Header({required this.detail});

  final ConversationDetail detail;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final seller = detail.role == ConversationRole.SELLER;
    final listing = detail.listing;
    final dealerPage = detail.dealerPage;
    final online = listing != null && (listing.online ?? false) && listing.id != null;

    final who = Semantics(
      container: true,
      child: Wrap(
        spacing: AppSpace.s1,
        runSpacing: AppSpace.s0,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          SellerBadge(role: detail.counterpart?.role?.name),
          Text(seller ? 'intéressé par votre annonce' : 'vendeur de cette annonce', style: theme.textTheme.bodySmall),
        ],
      ),
    );

    final Widget about;
    VoidCallback? onTap;
    if (listing != null) {
      onTap = online ? () => context.push('/annonces/${listing.id}') : null;
      about = Semantics(
        container: true,
        link: online,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              listing.title ?? '',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.titleMedium?.copyWith(color: online ? AppColors.ink : AppColors.inkSoft),
            ),
            if (!online)
              Text('Plus en ligne', style: theme.textTheme.labelSmall)
            else if (listing.priceAmount != null)
              Text(
                formatPrice(listing.priceAmount!, currency: listing.currencyCode ?? 'EUR'),
                style: theme.textTheme.labelLarge,
              ),
          ],
        ),
      );
    } else if (dealerPage != null) {
      onTap = dealerPage.slug == null ? null : () => context.push('/professionnels/${dealerPage.slug}');
      about = Text(
        seller
            ? 'Message envoyé depuis votre page professionnelle.'
            : 'Message envoyé depuis la page du professionnel.',
        style: theme.textTheme.bodySmall?.copyWith(color: AppColors.accentStrong, fontWeight: AppType.semibold),
      );
    } else {
      about = Text('L’annonce n’existe plus.', style: theme.textTheme.bodySmall);
    }

    return AppCard(
      onTap: onTap,
      padding: const EdgeInsets.all(AppSpace.s3),
      child: Row(
        children: [
          if (listing != null) ...[ListingThumb(url: listing.coverUrl), const SizedBox(width: AppSpace.s3)],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                who,
                const SizedBox(height: AppSpace.s1),
                about,
              ],
            ),
          ),
          if (onTap != null) ...[
            const SizedBox(width: AppSpace.s2),
            const Icon(LucideIcons.chevronRight, size: AppSizes.icon, color: AppColors.inkFaint),
          ],
        ],
      ),
    );
  }
}

/// The messages, by day, newest at the bottom (a reversed list: it opens at the end).
class _Thread extends ConsumerWidget {
  const _Thread({required this.id, required this.detail, required this.reportedHere});

  final String id;
  final ConversationDetail detail;
  final Set<String> reportedHere;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final entries = <Widget>[];
    if (detail.earlierOmitted ?? false) {
      entries.add(
        Padding(
          padding: const EdgeInsets.all(AppSpace.s3),
          child: Text(
            'Les messages les plus anciens de cette conversation ne sont pas affichés.',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
      );
    }
    String? day;
    for (final message in detail.messages ?? const <MessageView>[]) {
      final sentAt = message.sentAt;
      final label = sentAt == null ? '' : dayLabel(sentAt);
      if (label != day) {
        day = label;
        entries.add(_DayHeading(label));
      }
      entries.add(
        MessageBubble(
          message: message,
          counterpart: detail.counterpart?.displayName ?? '',
          reported: (message.reportedByMe ?? false) || reportedHere.contains(message.id),
          onReport: () => _report(context, ref, message),
        ),
      );
    }
    return ColoredBox(
      color: AppColors.page,
      child: ListView(
        reverse: true,
        padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter, vertical: AppSpace.s3),
        children: entries.reversed.toList(),
      ),
    );
  }

  Future<void> _report(BuildContext context, WidgetRef ref, MessageView message) async {
    final messageId = message.id;
    if (messageId == null) {
      return;
    }
    final outcome = await ReportSheet.open<MessageReportReason>(
      context,
      title: 'Signaler ce message',
      reasons: messageReportReasons,
      noteMaxLength: messageReportNoteMaxLength,
      hint: 'Le message reste visible jusqu’à la décision d’un modérateur. L’autre personne n’est pas prévenue.',
      send: (reason, note) async {
        try {
          // Once per message: the API accepts a second report and changes nothing.
          await ref
              .read(messagesApiProvider)
              .report(
                id: messageId,
                reportMessageRequest: ReportMessageRequest(reason: reason, note: note),
              );
          return ReportOutcome.reported;
        } on Object catch (error) {
          throw ApiFailure.from(error);
        }
      },
    );
    if (outcome != null) {
      ref.read(conversationProvider(id).notifier).markReported(messageId);
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(const SnackBar(content: Text('Merci : le message a été signalé aux modérateurs.')));
      }
    }
  }
}

class _DayHeading extends StatelessWidget {
  const _DayHeading(this.label);

  final String label;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: AppSpace.s2),
    child: Semantics(
      header: true,
      child: Text(label, textAlign: TextAlign.center, style: Theme.of(context).textTheme.labelMedium),
    ),
  );
}

/// One message. TalkBack reads who wrote it, what, and when ("Jean : Toujours disponible ?, mardi
/// 21 octobre 2026 à 14:32"); "Signaler ce message" is its own button, on the other side's messages.
/// The text is shown as typed: plain text, never markup, and no link made tappable — as on the web.
class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.message,
    required this.counterpart,
    required this.reported,
    required this.onReport,
  });

  final MessageView message;
  final String counterpart;
  final bool reported;
  final VoidCallback onReport;

  String get _text {
    if (message.hidden ?? false) {
      return 'Message masqué par la modération.';
    }
    if (message.erased ?? false) {
      return 'Message effacé : son auteur a supprimé son compte.';
    }
    return message.body ?? '';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final mine = message.sentByMe ?? false;
    final quiet = (message.hidden ?? false) || (message.erased ?? false);
    final reportable = !mine && !quiet;
    final who = mine ? 'Vous' : counterpart;
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.8),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: AppSpace.s1),
          child: Column(
            crossAxisAlignment: mine ? CrossAxisAlignment.end : CrossAxisAlignment.start,
            children: [
              Semantics(
                container: true,
                label: '$who : $_text, ${sentAtFull(message.sentAt)}${reported ? ', signalé' : ''}',
                child: ExcludeSemantics(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: AppSpace.s4, vertical: AppSpace.s3),
                    decoration: BoxDecoration(
                      color: mine ? AppColors.accent : AppColors.surface,
                      // Round, but for the corner on the writer's side, by the edge they write from.
                      borderRadius: BorderRadius.only(
                        topLeft: const Radius.circular(AppRadius.lg),
                        topRight: const Radius.circular(AppRadius.lg),
                        bottomLeft: Radius.circular(mine ? AppRadius.lg : AppRadius.xs),
                        bottomRight: Radius.circular(mine ? AppRadius.xs : AppRadius.lg),
                      ),
                      boxShadow: mine ? null : AppShadows.card,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _text,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: mine ? AppColors.accentInk : AppColors.ink,
                            fontStyle: quiet ? FontStyle.italic : null,
                          ),
                        ),
                        const SizedBox(height: AppSpace.s1),
                        Text(
                          [timeOf(message.sentAt), if (reported) 'Signalé'].join(' · '),
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: mine ? AppColors.accentInk : AppColors.inkFaint,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              if (reportable && !reported)
                Semantics(
                  button: true,
                  label: 'Signaler ce message',
                  excludeSemantics: true,
                  child: TextButton.icon(
                    style: TextButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      foregroundColor: AppColors.inkFaint,
                    ),
                    onPressed: onReport,
                    icon: const Icon(LucideIcons.flag, size: AppSizes.iconTiny),
                    label: const Text('Signaler'),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The box to answer in. Sending disables it; a failure keeps the text and says why, and sending
/// again is the person's choice — the API takes no idempotency key, so the app never sends twice
/// on its own.
class Composer extends ConsumerStatefulWidget {
  const Composer({super.key, required this.id, required this.role});

  final String id;
  final ConversationRole? role;

  @override
  ConsumerState<Composer> createState() => _ComposerState();
}

class _ComposerState extends ConsumerState<Composer> {
  final _draft = TextEditingController();
  bool _sending = false;
  String? _failure;

  @override
  void initState() {
    super.initState();
    _draft.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _draft.dispose();
    super.dispose();
  }

  bool get _canSend => canSendMessage(_draft.text) && !_sending;

  Future<void> _send() async {
    if (!_canSend) {
      return;
    }
    setState(() {
      _sending = true;
      _failure = null;
    });
    try {
      await ref.read(conversationProvider(widget.id).notifier).send(_draft.text);
      if (mounted) {
        _draft.clear();
      }
    } on ApiFailure catch (failure) {
      if (mounted) {
        setState(() => _failure = sendFailure(failure));
      }
    } finally {
      if (mounted) {
        setState(() => _sending = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final seller = widget.role == ConversationRole.SELLER;
    // A pill on a grey fill, no outline: the composer, not a form.
    const pill = OutlineInputBorder(
      borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
      borderSide: BorderSide.none,
    );
    return DecoratedBox(
      decoration: const BoxDecoration(color: AppColors.surface, boxShadow: AppShadows.floating),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s3, AppSpace.s3, AppSpace.s2),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (_failure != null) ...[FormError(_failure), const SizedBox(height: AppSpace.s1)],
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _draft,
                      enabled: !_sending,
                      minLines: 1,
                      maxLines: 5,
                      maxLength: messageMaxLength,
                      keyboardType: TextInputType.multiline,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: const InputDecoration(
                        // The label stays the field's name for TalkBack; on screen it is the
                        // placeholder, and gives way to the text.
                        labelText: 'Votre réponse',
                        floatingLabelBehavior: FloatingLabelBehavior.never,
                        fillColor: AppColors.surfaceSunk,
                        contentPadding: EdgeInsets.symmetric(horizontal: AppSpace.s5, vertical: AppSpace.s3),
                        border: pill,
                        enabledBorder: pill,
                        disabledBorder: pill,
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
                          borderSide: BorderSide(color: AppColors.accent, width: 2),
                        ),
                        counterText: '',
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpace.s2),
                  IconButton.filled(
                    style: IconButton.styleFrom(
                      minimumSize: const Size.square(AppSizes.touch),
                      backgroundColor: AppColors.accent,
                      foregroundColor: AppColors.accentInk,
                      disabledBackgroundColor: AppColors.surfaceSunk,
                      disabledForegroundColor: AppColors.inkFaint,
                    ),
                    tooltip: _sending ? 'Envoi…' : 'Envoyer',
                    onPressed: _canSend ? _send : null,
                    icon: Icon(LucideIcons.send, size: AppSizes.icon, semanticLabel: _sending ? 'Envoi…' : 'Envoyer'),
                  ),
                ],
              ),
              const SizedBox(height: AppSpace.s1),
              Text(
                seller
                    ? 'Ne remettez jamais le véhicule avant que le paiement soit encaissé, et faites vérifier tout '
                          'chèque de banque par la banque qui l’a émis.'
                    : 'Ne payez jamais un véhicule avant de l’avoir vu.',
                style: theme.textTheme.bodySmall,
              ),
              ColdStartHint(pending: _sending),
            ],
          ),
        ),
      ),
    );
  }
}
