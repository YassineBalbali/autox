import 'package:autox_api/autox_api.dart' show CatalogueSuggestion;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';
import 'package:material_ui/material_ui.dart';

import '../../core/format/numbers.dart';
import '../../core/theme/tokens.dart';
import 'search_locations.dart';
import 'search_providers.dart';
import 'search_query.dart';

/// Typing a search: a make, a model or any words, with the catalogue's suggestions under the
/// field — the web's search bar and typeahead. [base] is the search being refined; its other
/// filters stay.
class SearchEntryScreen extends ConsumerStatefulWidget {
  const SearchEntryScreen({super.key, this.base = SearchQuery.empty});

  final SearchQuery base;

  @override
  ConsumerState<SearchEntryScreen> createState() => _SearchEntryScreenState();
}

class _SearchEntryScreenState extends ConsumerState<SearchEntryScreen> {
  late final _controller = TextEditingController(text: widget.base.text ?? '');
  String _typed = '';

  @override
  void initState() {
    super.initState();
    _typed = _controller.text;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  /// The words, searched as words.
  void _submit() {
    final text = _controller.text.trim();
    _open(widget.base.copyWith(text: text.isEmpty ? null : text));
  }

  /// A suggestion is its make and model; the words go, since "Audi A3" as words would also have
  /// to appear in a listing's title — the web's rule.
  void _pick(CatalogueSuggestion suggestion) =>
      _open(widget.base.copyWith(text: null, make: suggestion.makeSlug, model: suggestion.modelSlug));

  void _open(SearchQuery query) => context.pushReplacement(resultsLocation(query));

  @override
  Widget build(BuildContext context) {
    final suggestions = ref.watch(suggestionsProvider(_typed)).value ?? const <CatalogueSuggestion>[];

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: TextField(
          controller: _controller,
          autofocus: true,
          textInputAction: TextInputAction.search,
          decoration: const InputDecoration(hintText: 'Marque, modèle, mot-clé', border: InputBorder.none),
          onChanged: (text) => setState(() => _typed = text),
          onSubmitted: (_) => _submit(),
        ),
        actions: [IconButton(tooltip: 'Rechercher', icon: const Icon(LucideIcons.search), onPressed: _submit)],
      ),
      body: ListView.separated(
        itemCount: suggestions.length,
        separatorBuilder: (_, _) => const Divider(),
        itemBuilder: (_, index) {
          final suggestion = suggestions[index];
          return ListTile(
            leading: const Icon(LucideIcons.car, size: 20, color: AppColors.inkFaint),
            title: Text(suggestion.label),
            trailing: Text(suggestionCountLabel(suggestion.listingCount), style: Theme.of(context).textTheme.bodySmall),
            onTap: () => _pick(suggestion),
          );
        },
      ),
    );
  }
}

/// "1 annonce", "12 annonces", as under the web's field.
String suggestionCountLabel(int count) => '${formatInteger(count)} ${count < 2 ? 'annonce' : 'annonces'}';
