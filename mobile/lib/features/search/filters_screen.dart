import 'package:autox_api/autox_api.dart'
    show BodyType, DamageFilter, FuelType, Gearbox, LeasingType, Make, Model, VehicleCondition;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/labels/labels.g.dart';
import '../../core/reference/reference_providers.dart';
import '../../core/theme/tokens.dart';
import 'results_screen.dart' show resultCountLabel;
import 'search_providers.dart';
import 'search_query.dart';

/// Every filter of the site's sidebar, full screen: the web's `search-filters.html`, in its order
/// and with its words. Works on a draft; "Voir N annonces" applies it, the close button drops it.
/// Pops with the new query, or nothing. Acheter / Louer is not here but on the results page,
/// where changing it also clears what only meant something in the other search.
class FiltersScreen extends ConsumerStatefulWidget {
  const FiltersScreen({super.key, required this.initial});

  final SearchQuery initial;

  @override
  ConsumerState<FiltersScreen> createState() => _FiltersScreenState();
}

class _FiltersScreenState extends ConsumerState<FiltersScreen> {
  late SearchQuery _draft = widget.initial;

  /// Bumped by "Effacer" so the text fields are rebuilt empty.
  int _generation = 0;

  /// The draft as it would be searched: through the site's parameters and back, so ranges are
  /// ordered, numbers bounded and rules applied exactly as a link would be.
  SearchQuery get _normalised => parseQuery(toQueryParams(_draft));

  void _update(SearchQuery next) => setState(() => _draft = next);

  void _clear() => setState(() {
    _draft = withoutFilters(_draft);
    _generation++;
  });

  @override
  Widget build(BuildContext context) {
    final draft = _draft;
    final leasing = draft.isLeasing;
    final count = ref.watch(draftCountProvider(_normalised));
    final enums = ref.watch(enumValuesProvider).value ?? const <String, List<String>>{};

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          tooltip: 'Fermer',
          icon: const Icon(LucideIcons.x),
          onPressed: () => Navigator.of(context).pop(),
        ),
        title: const Text('Filtres'),
        centerTitle: false,
        actions: [TextButton(onPressed: _clear, child: const Text('Effacer'))],
      ),
      body: ListView(
        key: ValueKey(_generation),
        padding: const EdgeInsets.only(bottom: AppSpace.s5),
        children: [
          _Section(
            title: 'Marque et modèle',
            child: _VehiclePickers(
              make: draft.make,
              model: draft.model,
              onMake: (slug) => _update(draft.copyWith(make: slug, model: null)),
              onModel: (slug) => _update(draft.copyWith(model: slug)),
            ),
          ),
          _Section(
            title: 'Année',
            child: _RangeFields(
              min: draft.yearMin,
              max: draft.yearMax,
              minHint: 'De',
              maxHint: 'À',
              minLabel: 'Année minimum',
              maxLabel: 'Année maximum',
              onChanged: (min, max) => _update(draft.copyWith(yearMin: min, yearMax: max)),
            ),
          ),
          if (leasing) ...[
            _Section(
              title: 'Mensualité (€/mois)',
              child: _RangeFields(
                min: draft.monthlyMin,
                max: draft.monthlyMax,
                minLabel: 'Mensualité minimum',
                maxLabel: 'Mensualité maximum',
                onChanged: (min, max) => _update(draft.copyWith(monthlyMin: min, monthlyMax: max)),
              ),
            ),
            _Section(
              title: 'Durée (mois)',
              child: _RangeFields(
                min: draft.durationMin,
                max: draft.durationMax,
                minLabel: 'Durée minimum',
                maxLabel: 'Durée maximum',
                onChanged: (min, max) => _update(draft.copyWith(durationMin: min, durationMax: max)),
              ),
            ),
            _Section(
              title: 'Kilométrage annuel minimum',
              child: _NumberField(
                value: draft.allowanceMin,
                hint: 'Ex. 15000',
                label: 'Kilométrage annuel minimum',
                onChanged: (value) => _update(draft.copyWith(allowanceMin: value)),
              ),
            ),
          ] else
            _Section(
              title: 'Prix (€)',
              child: _RangeFields(
                min: draft.priceMin,
                max: draft.priceMax,
                minLabel: 'Prix minimum en euros',
                maxLabel: 'Prix maximum en euros',
                onChanged: (min, max) => _update(draft.copyWith(priceMin: min, priceMax: max)),
              ),
            ),
          _Section(
            title: 'Kilométrage (km)',
            child: _RangeFields(
              min: draft.mileageMin,
              max: draft.mileageMax,
              minLabel: 'Kilométrage minimum',
              maxLabel: 'Kilométrage maximum',
              onChanged: (min, max) => _update(draft.copyWith(mileageMin: min, mileageMax: max)),
            ),
          ),
          if (!leasing) ...[
            _Section(
              title: 'Prix du marché',
              child: SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Bonnes affaires uniquement'),
                value: draft.goodDeals && allowsGoodDeals(damage: draft.damage, offer: draft.offer),
                onChanged: allowsGoodDeals(damage: draft.damage, offer: draft.offer)
                    ? (on) => _update(draft.copyWith(goodDeals: on))
                    : null,
              ),
            ),
            _Section(
              title: 'État du véhicule',
              child: _SingleChoice<DamageFilter>(
                options: [
                  for (final MapEntry(:key, :value) in damageFilterLabels.entries)
                    (DamageFilter.values.firstWhere((filter) => filter.value == key), value),
                ],
                selected: draft.damage,
                onSelected: (damage) => _update(
                  draft.copyWith(
                    damage: damage ?? DamageFilter.UNDAMAGED,
                    goodDeals: damage == DamageFilter.DAMAGED_ONLY ? false : draft.goodDeals,
                  ),
                ),
                allowNone: false,
              ),
            ),
          ],
          _Section(
            title: 'Crit’Air maximum',
            child: _SingleChoice<int>(
              options: critAirMaxOptions,
              selected: draft.critAirMax,
              onSelected: (value) => _update(draft.copyWith(critAirMax: value)),
            ),
          ),
          if (leasing)
            _Section(
              title: 'Type de location',
              child: _MultiChoice<LeasingType>(
                offered: _offered(enums['LeasingType'], LeasingType.values),
                label: leasingTypeLabel,
                selected: draft.leasingType,
                onChanged: (values) => _update(draft.copyWith(leasingType: values)),
              ),
            ),
          _Section(
            title: 'Carburant',
            child: _MultiChoice<FuelType>(
              offered: _offered(enums['FuelType'], FuelType.values),
              label: fuelTypeLabel,
              selected: draft.fuel,
              onChanged: (values) => _update(draft.copyWith(fuel: values)),
            ),
          ),
          _Section(
            title: 'Boîte de vitesses',
            child: _MultiChoice<Gearbox>(
              offered: _offered(enums['Gearbox'], Gearbox.values),
              label: gearboxLabel,
              selected: draft.gearbox,
              onChanged: (values) => _update(draft.copyWith(gearbox: values)),
            ),
          ),
          _Section(
            title: 'Carrosserie',
            child: _MultiChoice<BodyType>(
              offered: _offered(enums['BodyType'], BodyType.values),
              label: bodyTypeLabel,
              selected: draft.body,
              onChanged: (values) => _update(draft.copyWith(body: values)),
            ),
          ),
          _Section(
            title: 'Neuf ou occasion',
            child: _MultiChoice<VehicleCondition>(
              offered: _offered(enums['VehicleCondition'], VehicleCondition.values),
              label: vehicleConditionLabel,
              selected: draft.condition,
              onChanged: (values) => _update(draft.copyWith(condition: values)),
            ),
          ),
          _Section(
            title: 'Localisation',
            child: _TextField(
              value: draft.location,
              hint: 'Ville ou code postal',
              onChanged: (text) => _update(draft.copyWith(location: text)),
            ),
          ),
        ],
      ),
      bottomNavigationBar: SafeArea(
        child: Container(
          padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s2, AppSpace.gutter, AppSpace.s2),
          decoration: const BoxDecoration(
            color: AppColors.surface,
            border: Border(top: BorderSide(color: AppColors.line)),
          ),
          child: FilledButton(
            onPressed: () => Navigator.of(context).pop(_normalised),
            child: Text(
              count.hasValue && !count.isLoading ? 'Voir ${resultCountLabel(count.value!)}' : 'Voir les annonces',
            ),
          ),
        ),
      ),
    );
  }

  /// The values the API offers for an enum, in its order, that this build knows — the web's
  /// rule: a chip that would do nothing is worse than none until the app is updated. Before the
  /// API has answered, every known value, in the generated order.
  static List<E> _offered<E extends Enum>(List<String>? offered, List<E> known) {
    final usable = known.where((value) => value.toString() != 'unknown_default_open_api').toList();
    if (offered == null) {
      return usable;
    }
    return [for (final wire in offered) ...usable.where((value) => value.toString() == wire)];
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  // A Material, not a coloured box: the switch's tile paints its ink on the nearest Material.
  Widget build(BuildContext context) => Material(
    color: AppColors.surface,
    shape: const Border(bottom: BorderSide(color: AppColors.line)),
    child: Padding(
      padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s3, AppSpace.gutter, AppSpace.s3),
      // A container, so TalkBack groups the section; its visible title already names it.
      child: Semantics(
        container: true,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: AppType.bold)),
            const SizedBox(height: AppSpace.s2),
            child,
          ],
        ),
      ),
    ),
  );
}

/// A whole number, digits only; empty is no bound.
class _NumberField extends StatefulWidget {
  const _NumberField({required this.value, required this.onChanged, required this.label, this.hint});

  final int? value;
  final ValueChanged<int?> onChanged;
  final String label;
  final String? hint;

  @override
  State<_NumberField> createState() => _NumberFieldState();
}

class _NumberFieldState extends State<_NumberField> {
  late final _controller = TextEditingController(text: widget.value?.toString() ?? '');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Semantics(
    container: true,
    // For TalkBack: the hint alone ("Min") does not say of what.
    label: widget.label,
    child: TextField(
      controller: _controller,
      keyboardType: TextInputType.number,
      inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(9)],
      decoration: InputDecoration(hintText: widget.hint ?? 'Min', isDense: true, border: const OutlineInputBorder()),
      onChanged: (text) => widget.onChanged(int.tryParse(text)),
    ),
  );
}

class _RangeFields extends StatelessWidget {
  const _RangeFields({
    required this.min,
    required this.max,
    required this.onChanged,
    required this.minLabel,
    required this.maxLabel,
    this.minHint = 'Min',
    this.maxHint = 'Max',
  });

  final int? min;
  final int? max;
  final void Function(int? min, int? max) onChanged;
  final String minLabel;
  final String maxLabel;
  final String minHint;
  final String maxHint;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Expanded(
        child: _NumberField(value: min, hint: minHint, label: minLabel, onChanged: (value) => onChanged(value, max)),
      ),
      const SizedBox(width: AppSpace.s2),
      Expanded(
        child: _NumberField(value: max, hint: maxHint, label: maxLabel, onChanged: (value) => onChanged(min, value)),
      ),
    ],
  );
}

class _TextField extends StatefulWidget {
  const _TextField({required this.value, required this.onChanged, required this.hint});

  final String? value;
  final ValueChanged<String?> onChanged;
  final String hint;

  @override
  State<_TextField> createState() => _TextFieldState();
}

class _TextFieldState extends State<_TextField> {
  late final _controller = TextEditingController(text: widget.value ?? '');

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
    controller: _controller,
    decoration: InputDecoration(hintText: widget.hint, isDense: true, border: const OutlineInputBorder()),
    onChanged: (text) => widget.onChanged(text.trim().isEmpty ? null : text),
  );
}

/// Chips, several at once.
class _MultiChoice<E extends Enum> extends StatelessWidget {
  const _MultiChoice({required this.offered, required this.label, required this.selected, required this.onChanged});

  final List<E> offered;
  final String Function(E) label;
  final List<E> selected;
  final ValueChanged<List<E>> onChanged;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: AppSpace.s1,
    runSpacing: AppSpace.s1,
    children: [
      for (final value in offered)
        FilterChip(
          label: Text(label(value)),
          selected: selected.contains(value),
          onSelected: (on) => onChanged(on ? [...selected, value] : (List.of(selected)..remove(value))),
        ),
    ],
  );
}

/// Chips, one at a time; pressing the chosen one again clears it unless [allowNone] is false.
class _SingleChoice<T> extends StatelessWidget {
  const _SingleChoice({required this.options, required this.selected, required this.onSelected, this.allowNone = true});

  final List<(T, String)> options;
  final T? selected;
  final ValueChanged<T?> onSelected;
  final bool allowNone;

  @override
  Widget build(BuildContext context) => Wrap(
    spacing: AppSpace.s1,
    runSpacing: AppSpace.s1,
    children: [
      for (final (value, label) in options)
        ChoiceChip(
          label: Text(label),
          selected: selected == value,
          onSelected: (on) {
            if (on) {
              onSelected(value);
            } else if (allowNone) {
              onSelected(null);
            }
          },
        ),
    ],
  );
}

/// Make, then model, each from the catalogue.
class _VehiclePickers extends ConsumerWidget {
  const _VehiclePickers({required this.make, required this.model, required this.onMake, required this.onModel});

  final String? make;
  final String? model;
  final ValueChanged<String?> onMake;
  final ValueChanged<String?> onModel;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final makes = ref.watch(makesProvider).value ?? const <Make>[];
    final chosen = makes.where((candidate) => candidate.slug == make).firstOrNull;
    final models = chosen?.id == null
        ? const <Model>[]
        : ref.watch(modelsProvider(chosen!.id!)).value ?? const <Model>[];
    final modelName = models.where((candidate) => candidate.slug == model).firstOrNull?.name;

    return Column(
      children: [
        _PickerTile(
          label: 'Marque',
          value: chosen?.name ?? make,
          placeholder: 'Toutes les marques',
          onTap: () async {
            final picked = await _pick(
              context,
              title: 'Marque',
              none: 'Toutes les marques',
              options: [for (final item in popularFirst(makes)) (item.slug ?? '', item.name ?? '')],
            );
            if (picked != null) {
              onMake(picked.isEmpty ? null : picked);
            }
          },
        ),
        const SizedBox(height: AppSpace.s2),
        _PickerTile(
          label: 'Modèle',
          value: make == null ? null : (modelName ?? model),
          placeholder: make == null ? 'Choisissez d’abord une marque' : 'Tous les modèles',
          onTap: make == null || models.isEmpty
              ? null
              : () async {
                  final picked = await _pick(
                    context,
                    title: 'Modèle',
                    none: 'Tous les modèles',
                    options: [for (final item in models) (item.slug ?? '', item.name ?? '')],
                  );
                  if (picked != null) {
                    onModel(picked.isEmpty ? null : picked);
                  }
                },
        ),
      ],
    );
  }

  /// A searchable list in a sheet. Returns the slug, '' for "all", or null when dismissed.
  static Future<String?> _pick(
    BuildContext context, {
    required String title,
    required String none,
    required List<(String, String)> options,
  }) => showModalBottomSheet<String>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    builder: (_) => _PickerSheet(title: title, none: none, options: options),
  );
}

class _PickerTile extends StatelessWidget {
  const _PickerTile({required this.label, required this.value, required this.placeholder, required this.onTap});

  final String label;
  final String? value;
  final String placeholder;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) => InputDecorator(
    decoration: InputDecoration(
      labelText: label,
      isDense: true,
      enabled: onTap != null,
      border: const OutlineInputBorder(),
      suffixIcon: const Icon(LucideIcons.chevronDown, size: 18),
    ),
    child: InkWell(
      onTap: onTap,
      child: Text(value ?? placeholder, style: TextStyle(color: value == null ? AppColors.inkFaint : AppColors.ink)),
    ),
  );
}

class _PickerSheet extends StatefulWidget {
  const _PickerSheet({required this.title, required this.none, required this.options});

  final String title;
  final String none;
  final List<(String, String)> options;

  @override
  State<_PickerSheet> createState() => _PickerSheetState();
}

class _PickerSheetState extends State<_PickerSheet> {
  String _filter = '';

  @override
  Widget build(BuildContext context) {
    final filter = _filter.trim().toLowerCase();
    final shown = widget.options.where((option) => option.$2.toLowerCase().contains(filter)).toList();
    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.85,
      builder: (context, scroll) => Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(AppSpace.gutter, AppSpace.s3, AppSpace.gutter, AppSpace.s2),
            child: TextField(
              autofocus: false,
              decoration: InputDecoration(
                hintText: widget.title,
                prefixIcon: const Icon(LucideIcons.search, size: 18),
                isDense: true,
                border: const OutlineInputBorder(),
              ),
              onChanged: (text) => setState(() => _filter = text),
            ),
          ),
          Expanded(
            child: ListView(
              controller: scroll,
              children: [
                if (filter.isEmpty) ListTile(title: Text(widget.none), onTap: () => Navigator.of(context).pop('')),
                for (final (slug, name) in shown)
                  ListTile(title: Text(name), onTap: () => Navigator.of(context).pop(slug)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
