import 'dart:async';

import 'package:autox_api/autox_api.dart' show ConversationDetail, DealerPage, PublicListingDetail, SendMessageRequest;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/api/api_failure.dart';
import '../../core/auth/session.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/pending.dart';
import '../account/account_locations.dart';
import '../account/widgets/form_parts.dart' show FormError;
import '../account/widgets/verify_notice.dart';
import '../dealers/dealer_providers.dart';
import 'messages_locations.dart';
import 'messaging_providers.dart';
import 'messaging_text.dart';

/// Who is written to: a listing's seller, about the listing, or a dealer from their page.
class ContactTarget {
  ContactTarget.listing(PublicListingDetail listing)
    : sellerId = listing.seller?.id,
      name = listing.seller?.displayName ?? '',
      demo = listing.seller?.demo ?? false,
      listingId = listing.id,
      dealerSlug = null,
      back = '/annonces/${listing.id}',
      greeting = contactGreeting(listing.title ?? '');

  ContactTarget.dealer(DealerPage dealer)
    : sellerId = dealer.sellerId,
      name = dealer.name ?? '',
      demo = dealer.demo ?? false,
      listingId = null,
      dealerSlug = dealer.slug,
      back = '/professionnels/${dealer.slug}',
      greeting = contactGreeting(null);

  final String? sellerId;
  final String name;
  final bool demo;
  final String? listingId;
  final String? dealerSlug;

  /// The page it is written from, where signing in comes back to.
  final String back;

  /// The first line of the message, to edit: most buyers ask the same thing first.
  final String greeting;

  bool get fromDealerPage => dealerSlug != null;
}

/// Sends a first message (or the next one: the API continues the conversation a buyer already
/// has about the same listing, or from the same dealer page). Overridden in tests.
final contactSenderProvider = Provider<Future<ConversationDetail> Function(ContactTarget, String)>((ref) {
  return (target, body) async {
    try {
      final request = SendMessageRequest(body: body.trim());
      final reply = target.dealerSlug != null
          ? await ref.read(dealersApiProvider).contactDealer(slug: target.dealerSlug!, sendMessageRequest: request)
          : await ref
                .read(messagesApiProvider)
                .contactSeller(listingId: target.listingId!, sendMessageRequest: request);
      unawaited(ref.read(inboxProvider.notifier).refresh());
      unawaited(ref.read(unreadCountProvider.notifier).refresh());
      return reply.data!;
    } on Object catch (error) {
      throw ApiFailure.from(error);
    }
  };
});

/// "Contacter le vendeur", the web's `app-contact-seller`, in its states: signed out, sign in and
/// come back here with the message open; the seller's own listing or page, a pointer to their
/// messages; an address to verify first, what to do about it; anyone else, the message, written
/// in a sheet with a first line already there; sent, a confirmation and the way to the
/// conversation. Writing again continues the same conversation, so there is no "already
/// contacted" state: the conversation opened after sending is the one that already existed.
class ContactSeller extends ConsumerStatefulWidget {
  const ContactSeller({super.key, required this.target, this.openOnArrival = false});

  final ContactTarget target;

  /// Arrived with the message to write (`?ecrire=1`, back from signing in on another path).
  final bool openOnArrival;

  @override
  ConsumerState<ContactSeller> createState() => _ContactSellerState();
}

class _ContactSellerState extends ConsumerState<ContactSeller> {
  final _draft = TextEditingController();
  String? _sentTo;

  @override
  void initState() {
    super.initState();
    if (widget.openOnArrival) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _canWrite) {
          unawaited(_write());
        }
      });
    }
  }

  @override
  void dispose() {
    _draft.dispose();
    super.dispose();
  }

  String? get _me => switch (ref.read(sessionProvider)) {
    SignedIn(:final user) => user.id,
    _ => null,
  };

  bool get _own => _me != null && _me == widget.target.sellerId;

  bool get _canWrite => ref.read(sessionProvider) is SignedIn && !_own && !ref.read(mustVerifyProvider);

  /// Signed out: the sign-in form, then back here with the message open.
  Future<void> _signIn() async {
    final signedIn = await signInFirst(context, back: '${widget.target.back}?ecrire=1');
    if (signedIn && mounted && _canWrite) {
      await _write();
    }
  }

  Future<void> _write() async {
    if (_draft.text.isEmpty) {
      _draft.text = widget.target.greeting;
    }
    final sent = await showModalBottomSheet<ConversationDetail>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => ContactComposer(target: widget.target, draft: _draft),
    );
    if (sent != null && mounted) {
      setState(() {
        _draft.clear();
        _sentTo = sent.id;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final session = ref.watch(sessionProvider);
    final mustVerify = ref.watch(mustVerifyProvider);
    final target = widget.target;

    final Widget body;
    if (session is! SignedIn) {
      body = _Action(
        onPressed: _signIn,
        note:
            'Connectez-vous pour écrire au vendeur. Vos échanges passent par AutoX : ni votre adresse e-mail ni '
            'votre numéro ne lui sont communiqués.',
      );
    } else if (_own) {
      body = Wrap(
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            target.fromDealerPage
                ? 'C’est votre page. Les messages des visiteurs arrivent dans '
                : 'C’est votre annonce. Les messages des acheteurs arrivent dans ',
            style: theme.textTheme.bodyMedium,
          ),
          TextButton(onPressed: () => context.go(MessagesLocations.inbox), child: const Text('vos messages')),
        ],
      );
    } else if (mustVerify) {
      body = const VerifyEmailNotice(
        'Vérifiez votre adresse e-mail pour écrire au vendeur : cliquez sur le lien que nous vous avons envoyé.',
      );
    } else if (_sentTo != null) {
      body = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            container: true,
            liveRegion: true,
            child: Container(
              padding: const EdgeInsets.all(AppSpace.s3),
              decoration: BoxDecoration(
                color: AppColors.successSoft,
                borderRadius: BorderRadius.circular(AppRadius.md),
              ),
              child: Text(
                'Message envoyé. La réponse du vendeur arrivera dans vos messages.',
                style: theme.textTheme.bodyMedium?.copyWith(color: AppColors.success),
              ),
            ),
          ),
          const SizedBox(height: AppSpace.s2),
          OutlinedButton(
            onPressed: () => context.push(MessagesLocations.conversation(_sentTo!)),
            child: const Text('Voir la conversation'),
          ),
        ],
      );
    } else {
      body = _Action(
        onPressed: _write,
        note: 'Votre message passe par AutoX : vos coordonnées ne sont pas communiquées au vendeur.',
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        body,
        // The demo's own sellers: nobody signs in to them, so a buyer is told before writing.
        if (target.demo && !_own) ...[
          const SizedBox(height: AppSpace.s2),
          Semantics(
            container: true,
            child: Text(
              'Ce vendeur fait partie de la démonstration et ne répondra pas aux messages.',
              style: theme.textTheme.bodySmall,
            ),
          ),
        ],
      ],
    );
  }
}

class _Action extends StatelessWidget {
  const _Action({required this.onPressed, required this.note});

  final VoidCallback onPressed;
  final String note;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      FilledButton.icon(
        onPressed: onPressed,
        icon: const Icon(LucideIcons.messageCircle, size: 18),
        label: const Text('Contacter le vendeur'),
      ),
      const SizedBox(height: AppSpace.s1),
      Semantics(container: true, child: Text(note, style: Theme.of(context).textTheme.bodySmall)),
    ],
  );
}

/// The message to a seller, in a sheet: the text (a first line already written), its count,
/// the safety advice, "Envoyer". A failure keeps the text and says why; sending again is the
/// person's choice, never the app's.
class ContactComposer extends ConsumerStatefulWidget {
  const ContactComposer({super.key, required this.target, required this.draft});

  final ContactTarget target;
  final TextEditingController draft;

  @override
  ConsumerState<ContactComposer> createState() => _ContactComposerState();
}

class _ContactComposerState extends ConsumerState<ContactComposer> {
  bool _sending = false;
  String? _failure;

  @override
  void initState() {
    super.initState();
    widget.draft.addListener(_changed);
  }

  @override
  void dispose() {
    widget.draft.removeListener(_changed);
    super.dispose();
  }

  void _changed() => setState(() {});

  Future<void> _send() async {
    if (!canSendMessage(widget.draft.text) || _sending) {
      return;
    }
    setState(() {
      _sending = true;
      _failure = null;
    });
    final navigator = Navigator.of(context);
    try {
      final sent = await ref.read(contactSenderProvider)(widget.target, widget.draft.text);
      navigator.pop(sent);
    } on ApiFailure catch (failure) {
      if (mounted) {
        setState(() {
          _sending = false;
          _failure = sendFailure(failure);
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [
              Semantics(header: true, child: Text('Écrire à ${widget.target.name}', style: theme.textTheme.titleLarge)),
              const SizedBox(height: AppSpace.s3),
              TextField(
                controller: widget.draft,
                autofocus: true,
                enabled: !_sending,
                minLines: 4,
                maxLines: 8,
                maxLength: messageMaxLength,
                keyboardType: TextInputType.multiline,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(labelText: 'Votre message', border: OutlineInputBorder()),
              ),
              if (_failure != null) ...[FormError(_failure), const SizedBox(height: AppSpace.s2)],
              Text(
                'Votre adresse e-mail et votre numéro restent privés. Ne payez jamais un véhicule avant de l’avoir '
                'vu, et méfiez-vous des demandes de virement, de mandat ou de code de carte prépayée.',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: AppSpace.s3),
              SubmitButton(
                label: 'Envoyer',
                pendingLabel: 'Envoi…',
                pending: _sending,
                onPressed: canSendMessage(widget.draft.text) ? _send : null,
              ),
              TextButton(onPressed: _sending ? null : () => Navigator.of(context).pop(), child: const Text('Annuler')),
            ],
          ),
        ),
      ),
    );
  }
}
