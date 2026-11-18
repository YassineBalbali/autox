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
import '../../core/widgets/surfaces.dart';
import '../account/account_locations.dart';
import '../listing/widgets/listing_tags.dart' show SellerBadge, imageCacheKey;
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
    return Scaffold(body: SafeArea(bottom: false, child: signedIn ? const _Inbox() : const _SignedOut()));
  }
}

/// The tab's big title, at the top of what scrolls.
const _title = SliverPadding(
  padding: EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s5, AppSpace.gutter, AppSpace.s4),
  sliver: SliverToBoxAdapter(child: ScreenTitle('Messages')),
);

/// What fills the rest of the tab under its title: a wait, a failure, an empty inbox.
Widget _below(Widget child) => CustomScrollView(
  physics: const AlwaysScrollableScrollPhysics(),
  slivers: [
    _title,
    SliverFillRemaining(
      hasScrollBody: false,
      child: Padding(
        padding: const EdgeInsets.only(bottom: AppSpace.s7),
        child: child,
      ),
    ),
  ],
);

class _SignedOut extends StatelessWidget {
  const _SignedOut();

  @override
  Widget build(BuildContext context) => _below(
    Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpace.s5),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const ComingSoonView(
            icon: LucideIcons.messageSquare,
            title: AppLabels.messagesSignedOut,
            body: 'Vos conversations avec les vendeurs sont liées à votre compte.',
          ),
          FilledButton(
            style: FilledButton.styleFrom(minimumSize: const Size(0, AppSizes.control)),
            onPressed: () => context.go(AccountLocations.signIn),
            child: const Text('Se connecter'),
          ),
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
      return _below(const LoadingView(label: 'Chargement des messages'));
    }
    if (inbox.items.isEmpty && inbox.failure != null) {
      return _below(FailureView(error: inbox.failure!, onRetry: notifier.refresh));
    }
    return RefreshIndicator(
      onRefresh: () async {
        await notifier.refresh();
        await ref.read(unreadCountProvider.notifier).refresh();
      },
      child: inbox.items.isEmpty
          ? _below(
              const ComingSoonView(
                icon: LucideIcons.messageSquare,
                title: 'Aucune conversation',
                body: 'Écrivez à un vendeur depuis une annonce : vos échanges apparaîtront ici.',
              ),
            )
          : NotificationListener<ScrollNotification>(
              // The next page as the end comes into view.
              onNotification: (notification) {
                if (notification.metrics.extentAfter < 400) {
                  notifier.loadMore();
                }
                return false;
              },
              child: CustomScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                slivers: [
                  _title,
                  SliverPadding(
                    padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s5),
                    sliver: SliverList.separated(
                      itemCount: inbox.items.length + (inbox.hasNext ? 1 : 0),
                      separatorBuilder: (_, _) => const SizedBox(height: AppSpace.s2),
                      itemBuilder: (context, index) => index == inbox.items.length
                          ? const Padding(
                              padding: EdgeInsets.all(AppSpace.s4),
                              child: Center(child: CircularProgressIndicator()),
                            )
                          : InboxTile(item: inbox.items[index], unread: inbox.unreadOf(inbox.items[index])),
                    ),
                  ),
                ],
              ),
            ),
    );
  }
}

/// One conversation of the inbox, as a card: the listing's photo, who, about which car, the last
/// thing said, when, and how many of their messages wait. Unread ones are in full weight, as on
/// the web.
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
    return AppCard(
      onTap: item.id == null ? null : () => context.push(MessagesLocations.conversation(item.id!)),
      padding: const EdgeInsets.all(AppSpace.s3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ListingThumb(url: item.listing?.coverUrl),
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
                        style: theme.textTheme.titleMedium?.copyWith(fontWeight: strong ? AppType.heavy : AppType.bold),
                      ),
                    ),
                    const SizedBox(width: AppSpace.s1),
                    SellerBadge(role: item.counterpart?.role?.name),
                    const Spacer(),
                    Text(
                      when,
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: strong ? AppColors.accentStrong : AppColors.inkFaint,
                        fontWeight: strong ? AppType.bold : AppType.regular,
                      ),
                    ),
                  ],
                ),
                Text(_about, maxLines: 1, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodySmall),
                const SizedBox(height: AppSpace.s1),
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
                  constraints: const BoxConstraints(minWidth: AppSpace.s5),
                  padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
                  decoration: const BoxDecoration(
                    color: AppColors.accent,
                    borderRadius: BorderRadius.all(Radius.circular(AppRadius.pill)),
                  ),
                  child: Text(
                    '$unread',
                    textAlign: TextAlign.center,
                    style: AppText.tag.copyWith(color: AppColors.accentInk, fontWeight: AppType.bold),
                  ),
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// A listing's small photo, rounded: in the inbox and over a conversation.
class ListingThumb extends StatelessWidget {
  const ListingThumb({super.key, required this.url});

  final String? url;

  @override
  Widget build(BuildContext context) {
    const placeholder = ColoredBox(
      color: AppColors.surfaceSunk,
      child: Center(
        child: Icon(LucideIcons.carFront, size: AppSizes.icon, color: AppColors.inkFaint),
      ),
    );
    return ExcludeSemantics(
      child: ClipRRect(
        borderRadius: const BorderRadius.all(Radius.circular(AppRadius.sm)),
        child: SizedBox(
          width: AppSizes.thumbWidth,
          height: AppSizes.thumbHeight,
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
