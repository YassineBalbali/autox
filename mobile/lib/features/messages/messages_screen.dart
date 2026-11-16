import 'package:autox_api/autox_api.dart' show ConversationRole, ConversationSummary;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/auth/session.dart';
import '../../core/labels/app_labels.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/states.dart';
import '../account/account_locations.dart';
import '../listing/widgets/listing_row.dart' show SellerBadge, imageCacheKey;
import 'messages_locations.dart';
import 'messaging_providers.dart';
import 'messaging_text.dart';

/// The Messages tab: every conversation, as buyer and as seller, newest first — the web's inbox.
/// Signed out, it asks to sign in.
class MessagesScreen extends ConsumerWidget {
  const MessagesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final signedIn = ref.watch(sessionProvider) is SignedIn;
    return Scaffold(
      appBar: AppBar(title: const Text('Messages'), centerTitle: false),
      body: signedIn ? const _Inbox() : const _SignedOut(),
    );
  }
}

class _SignedOut extends StatelessWidget {
  const _SignedOut();

  @override
  Widget build(BuildContext context) => Center(
    child: Padding(
      padding: const EdgeInsets.all(AppSpace.s5),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const ComingSoonView(
            icon: LucideIcons.messageSquare,
            title: AppLabels.messagesSignedOut,
            body: 'Vos conversations avec les vendeurs sont liées à votre compte.',
          ),
          FilledButton(onPressed: () => context.go(AccountLocations.signIn), child: const Text('Se connecter')),
        ],
      ),
    ),
  );
}

class _Inbox extends ConsumerWidget {
  const _Inbox();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final inbox = ref.watch(inboxProvider);
    final notifier = ref.read(inboxProvider.notifier);
    if (inbox.loading) {
      return const LoadingView(label: 'Chargement des messages');
    }
    if (inbox.items.isEmpty && inbox.failure != null) {
      return FailureView(error: inbox.failure!, onRetry: notifier.refresh);
    }
    return RefreshIndicator(
      onRefresh: () async {
        await notifier.refresh();
        await ref.read(unreadCountProvider.notifier).refresh();
      },
      child: inbox.items.isEmpty
          ? ListView(
              children: const [
                SizedBox(height: AppSpace.s7),
                ComingSoonView(
                  icon: LucideIcons.messageSquare,
                  title: 'Aucune conversation',
                  body: 'Écrivez à un vendeur depuis une annonce : vos échanges apparaîtront ici.',
                ),
              ],
            )
          : NotificationListener<ScrollNotification>(
              // The next page as the end comes into view.
              onNotification: (notification) {
                if (notification.metrics.extentAfter < 400) {
                  notifier.loadMore();
                }
                return false;
              },
              child: ListView.separated(
                itemCount: inbox.items.length + (inbox.hasNext ? 1 : 0),
                separatorBuilder: (_, _) => const Divider(height: 1),
                itemBuilder: (context, index) => index == inbox.items.length
                    ? const Padding(
                        padding: EdgeInsets.all(AppSpace.s4),
                        child: Center(child: CircularProgressIndicator()),
                      )
                    : InboxTile(item: inbox.items[index], unread: inbox.unreadOf(inbox.items[index])),
              ),
            ),
    );
  }
}

/// One line of the inbox: the listing's photo, who, about which car, the last thing said, when,
/// and how many of their messages wait. Unread lines are in full weight, as on the web.
class InboxTile extends StatelessWidget {
  const InboxTile({super.key, required this.item, required this.unread});

  final ConversationSummary item;
  final int unread;

  String get _about {
    final seller = item.role == ConversationRole.SELLER;
    if (item.dealerPage != null) {
      return seller ? 'Depuis votre page professionnelle' : 'Depuis sa page professionnelle';
    }
    final title = item.listing?.title ?? 'Annonce supprimée';
    return seller ? 'Votre annonce · $title' : title;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final strong = unread > 0;
    final when = sentAtShort(item.lastMessageAt);
    return Material(
      color: AppColors.surface,
      child: InkWell(
        onTap: item.id == null ? null : () => context.push(MessagesLocations.conversation(item.id!)),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.gutter, vertical: AppSpace.s3),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _Thumb(url: item.listing?.coverUrl),
              const SizedBox(width: AppSpace.s3),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Flexible(
                          child: Text(
                            item.counterpart?.displayName ?? '',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleSmall?.copyWith(
                              fontWeight: strong ? AppType.bold : AppType.semibold,
                            ),
                          ),
                        ),
                        const SizedBox(width: AppSpace.s1),
                        SellerBadge(role: item.counterpart?.role?.name),
                        const Spacer(),
                        Text(when, style: theme.textTheme.labelSmall),
                      ],
                    ),
                    Text(_about, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
                    Text(
                      inboxPreview(item.lastMessage),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        color: strong ? AppColors.ink : AppColors.inkSoft,
                        fontWeight: strong ? AppType.semibold : AppType.regular,
                        fontStyle: (item.lastMessage?.hidden ?? false) ? FontStyle.italic : null,
                      ),
                    ),
                  ],
                ),
              ),
              if (strong) ...[
                const SizedBox(width: AppSpace.s2),
                Semantics(
                  label: unreadLabel(unread),
                  child: ExcludeSemantics(
                    child: Container(
                      constraints: const BoxConstraints(minWidth: 22),
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(color: AppColors.accent, borderRadius: BorderRadius.circular(11)),
                      child: Text(
                        '$unread',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          color: AppColors.accentInk,
                          fontSize: AppType.txs,
                          fontWeight: AppType.bold,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    const placeholder = ColoredBox(
      color: AppColors.surfaceSunk,
      child: Center(child: Icon(LucideIcons.carFront, size: 22, color: AppColors.inkFaint)),
    );
    return ExcludeSemantics(
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppRadius.md),
        child: SizedBox(
          width: 64,
          height: 48,
          child: url == null
              ? placeholder
              : CachedNetworkImage(
                  imageUrl: url!,
                  cacheKey: imageCacheKey(url!),
                  fit: BoxFit.cover,
                  placeholder: (_, _) => placeholder,
                  errorWidget: (_, _, _) => placeholder,
                ),
        ),
      ),
    );
  }
}
