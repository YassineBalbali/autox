import 'package:autox_api/autox_api.dart' show DamageView, LeasingView, PriceIndicator, PriceLevel;
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/format/listing_text.dart';
import '../../../core/format/numbers.dart';
import '../../../core/labels/labels.g.dart';
import '../../../core/theme/tokens.dart';

/// The small parts a listing is shown with, on a result card, its page and a conversation: the
/// price of a leasing offer, the market verdict, the seller's kind, the damage notice.

/// The image's address without its query string: stable across the re-signing of its URL.
String imageCacheKey(String url) {
  final uri = Uri.tryParse(url);
  return uri == null ? url : uri.replace(query: '').toString().replaceFirst(RegExp(r'\?$'), '');
}

/// "à partir de 289 €/mois", with the terms under it.
class LeasingPrice extends StatelessWidget {
  const LeasingPrice({super.key, required this.leasing, this.currency = 'EUR', this.minorUnit = 2});

  final LeasingView leasing;
  final String currency;
  final int minorUnit;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('à partir de', style: theme.textTheme.bodySmall?.copyWith(fontWeight: AppType.semibold)),
        Text.rich(
          TextSpan(
            text: formatPrice(leasing.monthlyPayment ?? 0, currency: currency, minorUnit: minorUnit),
            style: AppText.price,
            children: [
              TextSpan(
                text: '/mois',
                style: AppText.tag.copyWith(color: AppColors.ink),
              ),
            ],
          ),
        ),
        Text(leasingTerms(leasing), style: theme.textTheme.labelMedium),
      ],
    );
  }
}

/// The market verdict: three segments, the listing's lit, and the word.
class PriceBadge extends StatelessWidget {
  const PriceBadge({super.key, required this.indicator});

  final PriceIndicator indicator;

  static const _levels = [PriceLevel.GOOD_DEAL, PriceLevel.MARKET, PriceLevel.ABOVE_MARKET];

  static Color colorOf(PriceLevel level) => switch (level) {
    PriceLevel.GOOD_DEAL => AppColors.success,
    PriceLevel.ABOVE_MARKET => AppColors.warn,
    _ => AppColors.accent,
  };

  @override
  Widget build(BuildContext context) {
    final level = indicator.level!;
    if (level == PriceLevel.unknownDefaultOpenApi) {
      return const SizedBox.shrink();
    }
    final color = colorOf(level);
    return Semantics(
      container: true,
      label: priceLevelLabel(level),
      excludeSemantics: true,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          for (final segment in _levels)
            Container(
              width: AppSizes.verdictSegmentWidth,
              height: AppSizes.verdictSegmentHeight,
              margin: const EdgeInsets.only(right: AppSpace.s0),
              decoration: BoxDecoration(
                color: segment == level ? color : AppColors.line,
                borderRadius: BorderRadius.circular(AppRadius.pill),
              ),
            ),
          const SizedBox(width: AppSpace.s1),
          Flexible(
            child: Text(
              priceLevelLabel(level),
              style: AppText.tag.copyWith(fontWeight: AppType.bold, color: color),
            ),
          ),
        ],
      ),
    );
  }
}

/// "Pro" or "Particulier"; nothing for any other role.
class SellerBadge extends StatelessWidget {
  const SellerBadge({super.key, required this.role});

  final String? role;

  @override
  Widget build(BuildContext context) {
    final (icon, label) = switch (role) {
      'PROFESSIONNEL' => (LucideIcons.store, 'Pro'),
      'PARTICULIER' => (LucideIcons.user, 'Particulier'),
      _ => (null, null),
    };
    if (label == null) {
      return const SizedBox.shrink();
    }
    final pro = role == 'PROFESSIONNEL';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
      decoration: BoxDecoration(
        color: pro ? AppColors.accentSoft : AppColors.surfaceSunk,
        borderRadius: BorderRadius.circular(AppRadius.pill),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: AppSizes.iconTiny, color: pro ? AppColors.accentStrong : AppColors.inkSoft),
          const SizedBox(width: AppSpace.s1),
          Text(label, style: AppText.tag.copyWith(color: pro ? AppColors.accentStrong : AppColors.inkSoft)),
        ],
      ),
    );
  }
}

/// "Véhicule endommagé" and what was declared: "Carrosserie, Grêle · Roulant".
class DamageTag extends StatelessWidget {
  const DamageTag({super.key, required this.damage});

  final DamageView damage;

  /// The facts after the tag, as the web's compact notice writes them.
  static String factsOf(DamageView damage) {
    final types = (damage.types ?? const []).map(damageTypeLabel).where((label) => label.isNotEmpty).join(', ');
    final state = switch (damage.running) {
      true => 'Roulant',
      false => 'Non roulant',
      null => null,
    };
    return [types, ?state].where((part) => part.isNotEmpty).join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    final facts = factsOf(damage);
    return Wrap(
      spacing: AppSpace.s1,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
          decoration: BoxDecoration(color: AppColors.dangerSoft, borderRadius: BorderRadius.circular(AppRadius.pill)),
          child: Text(
            'Véhicule endommagé',
            style: AppText.tag.copyWith(fontWeight: AppType.bold, color: AppColors.danger),
          ),
        ),
        if (facts.isNotEmpty) Text(facts, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}
