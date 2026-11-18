import 'package:autox_api/autox_api.dart' show CritAir, DamageView, LeasingType, LeasingView, PublicListingDetail;
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/format/listing_text.dart' show placeOf;
import '../../../core/format/numbers.dart';
import '../../../core/labels/labels.g.dart';
import '../../../core/theme/tokens.dart';
import '../../../core/widgets/surfaces.dart';
import '../listing_text.dart';
import 'listing_tags.dart' show DamageTag;

/// The parts of the listing page, each the web's component of the same name.

/// A titled block of a page (a listing's, a dealer's): a white rounded card on the grey, the
/// page's margins around it.
class PageSection extends StatelessWidget {
  const PageSection({super.key, required this.child, this.title});

  final String? title;
  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(AppSpace.gutter, 0, AppSpace.gutter, AppSpace.s3),
    child: AppCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (title != null) ...[
            Semantics(
              container: true,
              header: true,
              child: Text(title!, style: Theme.of(context).textTheme.titleMedium),
            ),
            const SizedBox(height: AppSpace.s3),
          ],
          // Its own node: otherwise the content's loose text becomes the section's own label, which
          // TalkBack reads before the heading.
          Semantics(container: true, child: child),
        ],
      ),
    ),
  );
}

/// The key facts as a grid of rounded tiles, two by two: year, mileage (or "Neuf"), energy,
/// gearbox, power.
class KeyFacts extends StatelessWidget {
  const KeyFacts({super.key, required this.listing});

  final PublicListingDetail listing;

  static List<(IconData, String, String)> factsOf(PublicListingDetail listing) {
    final vehicle = listing.vehicle;
    final registered = registration(vehicle?.firstRegistrationYear, vehicle?.firstRegistrationMonth);
    final facts = <(IconData, String, String)>[
      if (registered != null) (LucideIcons.calendar, 'Année', registered),
      if (listing.soldNew ?? false)
        (LucideIcons.sparkles, 'État', 'Neuf')
      else if (listing.mileageKm != null)
        (LucideIcons.gauge, 'Kilométrage', formatKm(listing.mileageKm!)),
      if (vehicle?.fuelType != null && fuelTypeLabel(vehicle!.fuelType!).isNotEmpty)
        (
          vehicle.fuelType!.value == 'ELECTRIC' ? LucideIcons.plugZap : LucideIcons.fuel,
          'Énergie',
          fuelTypeLabel(vehicle.fuelType!),
        ),
      if (vehicle?.gearbox != null && gearboxLabel(vehicle!.gearbox!).isNotEmpty)
        (LucideIcons.cog, 'Boîte de vitesses', gearboxLabel(vehicle.gearbox!)),
      if (vehicle?.powerHp != null) (LucideIcons.zap, 'Puissance', '${formatInteger(vehicle!.powerHp!)}${nbsp}ch'),
    ];
    return facts;
  }

  @override
  Widget build(BuildContext context) {
    final facts = factsOf(listing);
    if (facts.isEmpty) {
      return const SizedBox.shrink();
    }
    final theme = Theme.of(context);
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = (constraints.maxWidth - AppSpace.s2) / 2;
        return Wrap(
          spacing: AppSpace.s2,
          runSpacing: AppSpace.s2,
          children: [
            for (final (icon, label, value) in facts)
              Semantics(
                container: true,
                label: '$label : $value',
                excludeSemantics: true,
                child: Container(
                  width: width,
                  padding: const EdgeInsets.all(AppSpace.s3),
                  decoration: const BoxDecoration(
                    color: AppColors.surfaceSunk,
                    borderRadius: BorderRadius.all(Radius.circular(AppRadius.md)),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(icon, size: AppSizes.icon, color: AppColors.accentStrong),
                      const SizedBox(height: AppSpace.s2),
                      Text(label, style: theme.textTheme.labelMedium),
                      Text(value, style: theme.textTheme.titleMedium),
                    ],
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

/// The estimated Crit'Air vignette in its own colours, what it rests on, and the ZFE caveat.
class CritAirBadge extends StatelessWidget {
  const CritAirBadge({super.key, required this.critAir});

  final CritAir critAir;

  /// The vignettes' colours: 0 green, 1 violet, 2 yellow, 3 orange, 4 burgundy, 5 grey (the
  /// web's `crit-air.ts`).
  static (Color, Color) coloursOf(CritAir value) => switch (value) {
    CritAir.ZERO => (const Color(0xFF00A651), AppColors.surface),
    CritAir.ONE => (const Color(0xFF7D4E9D), AppColors.surface),
    CritAir.TWO => (const Color(0xFFFCD800), AppColors.ink),
    CritAir.THREE => (const Color(0xFFF08A00), AppColors.ink),
    CritAir.FOUR => (const Color(0xFF8B1D2C), AppColors.surface),
    CritAir.FIVE => (const Color(0xFF6E6E6E), AppColors.surface),
    _ => (AppColors.surfaceSunk, AppColors.inkSoft),
  };

  static int? numberOf(CritAir value) => switch (value) {
    CritAir.ZERO => 0,
    CritAir.ONE => 1,
    CritAir.TWO => 2,
    CritAir.THREE => 3,
    CritAir.FOUR => 4,
    CritAir.FIVE => 5,
    _ => null,
  };

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final (background, ink) = coloursOf(critAir);
    final number = numberOf(critAir);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ExcludeSemantics(
          child: Container(
            width: AppSizes.vignette,
            height: AppSizes.vignette,
            decoration: BoxDecoration(color: background, shape: BoxShape.circle),
            alignment: Alignment.center,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  number == null ? 'Non classé' : 'Crit’Air',
                  style: TextStyle(color: ink, fontSize: AppType.t2xs, fontWeight: AppType.semibold),
                ),
                if (number != null)
                  Text(
                    '$number',
                    style: TextStyle(color: ink, fontSize: AppType.tlg, fontWeight: AppType.heavy, height: 1),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(width: AppSpace.s3),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(critAirLabel(critAir), style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
              const SizedBox(height: AppSpace.s0),
              Text(
                'Crit’Air estimée d’après l’énergie et la date de 1re immatriculation',
                style: theme.textTheme.bodySmall,
              ),
              const SizedBox(height: AppSpace.s1),
              Text(
                'Dans les zones à faibles émissions (ZFE), chaque agglomération fixe les vignettes autorisées et les '
                'horaires : vérifiez les règles de la ville où vous roulez.',
                style: theme.textTheme.labelSmall,
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// A leasing offer's terms, with the legal mentions — the web's `leasing-offer.ts`.
class LeasingOffer extends StatelessWidget {
  const LeasingOffer({super.key, required this.leasing, this.priceAmount, this.currency = 'EUR', this.minorUnit = 2});

  final LeasingView leasing;
  final int? priceAmount;
  final String currency;
  final int minorUnit;

  String _money(int? amount) => formatPrice(amount ?? 0, currency: currency, minorUnit: minorUnit);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final loa = leasing.type == LeasingType.LOA;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: AppSpace.s2,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: AppSpace.s2, vertical: AppSpace.s0),
              decoration: BoxDecoration(color: AppColors.accentSoft, borderRadius: BorderRadius.circular(AppRadius.sm)),
              child: Text(
                leasingTypeTitle(leasing),
                style: const TextStyle(fontSize: AppType.txs, fontWeight: AppType.bold, color: AppColors.accentStrong),
              ),
            ),
            if (loa) Text('Exemple représentatif', style: theme.textTheme.labelMedium),
          ],
        ),
        const SizedBox(height: AppSpace.s2),
        _Term('Premier loyer (apport)', _money(leasing.firstPayment)),
        _Term(leasingMonthlyLabel(leasing), _money(leasing.monthlyPayment), major: true),
        _Term('Durée', '${leasing.durationMonths} mois'),
        _Term('Kilométrage', '${formatInteger(leasing.mileagePerYear ?? 0)} km/an'),
        if (priceAmount != null) _Term('Prix au comptant du véhicule', _money(priceAmount)),
        if (loa) _Term('Option d’achat finale', _money(leasing.optionPrice)),
        _Term(leasingTotalLabel(leasing), _money(leasing.totalCost), major: true),
        const SizedBox(height: AppSpace.s2),
        if (loa) ...[
          Text(
            creditWarning,
            style: theme.textTheme.bodySmall?.copyWith(fontWeight: AppType.bold, color: AppColors.ink),
          ),
          const SizedBox(height: AppSpace.s1),
          Text(loaWithdrawal, style: theme.textTheme.labelSmall),
        ] else
          Text(lldNote, style: theme.textTheme.labelSmall),
      ],
    );
  }
}

class _Term extends StatelessWidget {
  const _Term(this.label, this.value, {this.major = false});

  final String label;
  final String value;
  final bool major;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpace.s0),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(child: Text(label, style: theme.textTheme.bodyMedium)),
          const SizedBox(width: AppSpace.s2),
          Text(
            value,
            style: major
                ? const TextStyle(fontSize: AppType.tmd, fontWeight: AppType.bold, color: AppColors.accent)
                : theme.textTheme.bodyMedium?.copyWith(fontWeight: AppType.semibold),
          ),
        ],
      ),
    );
  }
}

/// "Véhicule endommagé", what was declared, and the seller's description of the damage.
class DamageNotice extends StatelessWidget {
  const DamageNotice({super.key, required this.damage});

  final DamageView damage;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.all(AppSpace.s3),
    decoration: BoxDecoration(color: AppColors.dangerSoft, borderRadius: BorderRadius.circular(AppRadius.md)),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        DamageTag(damage: damage),
        if ((damage.description ?? '').trim().isNotEmpty) ...[
          const SizedBox(height: AppSpace.s1),
          Text(damage.description!.trim(), style: Theme.of(context).textTheme.bodyMedium),
        ],
      ],
    ),
  );
}

/// "Caractéristiques": label and value, row by row.
class SpecTable extends StatelessWidget {
  const SpecTable({super.key, required this.rows});

  final List<SpecRow> rows;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      children: [
        for (final (index, row) in rows.indexed)
          Container(
            padding: const EdgeInsets.symmetric(vertical: AppSpace.s2),
            decoration: index == 0
                ? null
                : const BoxDecoration(
                    border: Border(top: BorderSide(color: AppColors.line)),
                  ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: Text(row.label, style: theme.textTheme.bodySmall)),
                const SizedBox(width: AppSpace.s2),
                Expanded(
                  child: Text(row.value, style: theme.textTheme.bodyMedium?.copyWith(fontWeight: AppType.semibold)),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// "Équipements", by category.
class EquipmentList extends StatelessWidget {
  const EquipmentList({super.key, required this.groups});

  final List<FeatureGroup> groups;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        for (final group in groups) ...[
          Semantics(
            container: true,
            header: true,
            child: Text(group.title, style: theme.textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
          ),
          const SizedBox(height: AppSpace.s1),
          // The items as one node after their heading, read as a list: "ABS, ESP, …".
          Semantics(
            container: true,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                for (final item in group.items)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: AppSpace.s0),
                    child: Row(
                      children: [
                        const Icon(LucideIcons.check, size: AppSizes.iconSmall, color: AppColors.success),
                        const SizedBox(width: AppSpace.s2),
                        Expanded(child: Text(item, style: theme.textTheme.bodyMedium)),
                      ],
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpace.s3),
        ],
      ],
    );
  }
}

/// Who sells it: the name (a link to the dealer's page when there is one), the badge, the
/// place, since when.
class SellerCard extends StatelessWidget {
  const SellerCard({super.key, required this.listing, this.onOpenDealer});

  final PublicListingDetail listing;
  final VoidCallback? onOpenDealer;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final seller = listing.seller;
    final professional = seller?.role == 'PROFESSIONNEL';
    final location = listing.location;
    final place = placeOf(city: location?.city, postalCode: location?.postalCode, countryCode: location?.countryCode);
    final since = memberSince(seller?.memberSince);
    final name = Text(
      seller?.displayName ?? '',
      style: theme.textTheme.titleSmall?.copyWith(
        fontWeight: AppType.bold,
        color: onOpenDealer == null ? AppColors.ink : AppColors.accent,
      ),
    );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            CircleAvatar(
              radius: AppSizes.avatar / 2,
              backgroundColor: AppColors.surfaceSunk,
              child: Icon(
                professional ? LucideIcons.store : LucideIcons.user,
                size: AppSizes.icon,
                color: AppColors.inkSoft,
              ),
            ),
            const SizedBox(width: AppSpace.s3),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (onOpenDealer != null)
                    InkWell(
                      onTap: onOpenDealer,
                      child: Semantics(container: true, link: true, hint: 'Voir la page du professionnel', child: name),
                    )
                  else
                    name,
                  if (seller?.role == 'PROFESSIONNEL' || seller?.role == 'PARTICULIER')
                    // Its own node, read after the name rather than before it.
                    Semantics(
                      container: true,
                      child: Text(professional ? 'Professionnel' : 'Particulier', style: theme.textTheme.labelMedium),
                    ),
                ],
              ),
            ),
            if (onOpenDealer != null)
              const Icon(LucideIcons.chevronRight, size: AppSizes.icon, color: AppColors.inkFaint),
          ],
        ),
        if (place != null || since != null) const SizedBox(height: AppSpace.s2),
        if (place != null) _Line(icon: LucideIcons.mapPin, text: place),
        if (since != null) _Line(icon: LucideIcons.badgeCheck, text: since),
      ],
    );
  }
}

class _Line extends StatelessWidget {
  const _Line({required this.icon, required this.text});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Semantics(
    container: true,
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpace.s0),
      child: Row(
        children: [
          Icon(icon, size: AppSizes.iconSmall, color: AppColors.inkFaint),
          const SizedBox(width: AppSpace.s2),
          Expanded(child: Text(text, style: Theme.of(context).textTheme.bodySmall)),
        ],
      ),
    ),
  );
}
