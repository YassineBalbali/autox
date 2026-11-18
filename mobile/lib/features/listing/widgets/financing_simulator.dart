import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../../../core/format/numbers.dart';
import '../../../core/theme/tokens.dart';
import '../listing_text.dart';

/// "Simuler un financement" — the web's simulator: a down payment, a duration, an annual rate
/// (an example to replace), then the monthly payment, the amount borrowed, the total and the
/// cost of the credit, with the mandatory warning. Computed on the phone; nothing typed is
/// stored or sent (docs/financing-simulator.md).
class FinancingSimulator extends StatefulWidget {
  const FinancingSimulator({super.key, required this.price, this.currency = 'EUR', this.minorUnit = 2});

  /// The car's price, in minor units.
  final int price;
  final String currency;
  final int minorUnit;

  @override
  State<FinancingSimulator> createState() => _FinancingSimulatorState();
}

class _FinancingSimulatorState extends State<FinancingSimulator> {
  final _downPayment = TextEditingController(text: '0');
  final _rate = TextEditingController(text: _exampleRateText);
  int _months = 48;

  static final _exampleRateText = '$exampleRatePercent'.replaceFirst('.', ',');

  @override
  void dispose() {
    _downPayment.dispose();
    _rate.dispose();
    super.dispose();
  }

  int get _scale {
    var scale = 1;
    for (var i = 0; i < widget.minorUnit; i++) {
      scale *= 10;
    }
    return scale;
  }

  String _money(int amount) => formatPrice(amount, currency: widget.currency, minorUnit: widget.minorUnit);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final down = parseAmount(_downPayment.text);
    final rate = parseRate(_rate.text);
    final borrowed = down == null ? 0 : widget.price - down * _scale;

    // The web's messages, in the web's order.
    final String? error = switch (()) {
      _ when down == null => 'Saisissez un apport en euros, sans centimes.',
      _ when rate == null || rate > financingMaxRatePercent =>
        'Saisissez un taux entre 0 et $financingMaxRatePercent %, par exemple 5,9.',
      _ when borrowed <= 0 => 'Avec cet apport, il n’y a rien à financer.',
      _ => null,
    };
    final simulation = error == null
        ? simulateCredit(principal: borrowed, annualRatePercent: rate!, months: _months)
        : null;
    final symbol = widget.currency == 'EUR' ? '€' : widget.currency;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: TextField(
                controller: _downPayment,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[\d ]'))],
                decoration: InputDecoration(labelText: 'Apport ($symbol)', isDense: true),
                onChanged: (_) => setState(() {}),
              ),
            ),
            const SizedBox(width: AppSpace.s2),
            Expanded(
              child: DropdownButtonFormField<int>(
                initialValue: _months,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Durée', isDense: true),
                items: [
                  for (final months in financingDurations) DropdownMenuItem(value: months, child: Text('$months mois')),
                ],
                onChanged: (months) => setState(() => _months = months ?? _months),
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpace.s3),
        TextField(
          controller: _rate,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: 'Taux annuel (%)', isDense: true),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: AppSpace.s1),
        // Own nodes, here and below: loose text in a section is otherwise read before its fields.
        _Node(
          Text(
            'Taux d’exemple ($_exampleRateText %), pas une offre de crédit : remplacez-le par celui qu’on vous propose.',
            style: theme.textTheme.labelSmall,
          ),
        ),
        const SizedBox(height: AppSpace.s3),
        if (error != null)
          _Node(Text(error, style: theme.textTheme.bodySmall?.copyWith(color: AppColors.danger)))
        else if (simulation != null)
          Semantics(
            container: true,
            liveRegion: true,
            child: Column(
              children: [
                _Figure(label: 'Mensualité', value: '${_money(simulation.monthly)} /mois', major: true),
                _Figure(label: 'Montant emprunté', value: _money(borrowed)),
                _Figure(label: 'Montant total dû', value: _money(simulation.totalRepaid)),
                _Figure(label: 'Coût du crédit', value: _money(simulation.creditCost)),
              ],
            ),
          ),
        const SizedBox(height: AppSpace.s3),
        _Node(
          Text(
            creditWarning,
            style: theme.textTheme.bodySmall?.copyWith(fontWeight: AppType.bold, color: AppColors.ink),
          ),
        ),
        const SizedBox(height: AppSpace.s1),
        _Node(
          Text(
            'Simulation indicative, sans valeur d’offre de crédit : elle ne tient compte ni de l’assurance, ni des '
            'frais de dossier. Rien de ce que vous saisissez n’est enregistré ni transmis.',
            style: theme.textTheme.labelSmall,
          ),
        ),
      ],
    );
  }
}

/// A text read on its own, in its place on the screen.
class _Node extends StatelessWidget {
  const _Node(this.child);

  final Widget child;

  @override
  Widget build(BuildContext context) => Semantics(container: true, child: child);
}

class _Figure extends StatelessWidget {
  const _Figure({required this.label, required this.value, this.major = false});

  final String label;
  final String value;
  final bool major;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpace.s1),
      child: Row(
        children: [
          Expanded(child: Text(label, style: theme.textTheme.bodyMedium)),
          Text(
            value,
            style: major
                ? const TextStyle(fontSize: AppType.tlg, fontWeight: AppType.heavy, color: AppColors.accent)
                : theme.textTheme.bodyMedium?.copyWith(fontWeight: AppType.semibold),
          ),
        ],
      ),
    );
  }
}
