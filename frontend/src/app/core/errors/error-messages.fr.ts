import { ErrorCode } from '../api';

/**
 * French text for every error the API can return.
 *
 * <p><b>Typed as `Record<ErrorCode, string>` on purpose.</b> `ErrorCode` is generated from
 * the OpenAPI document, so when the backend adds an error code this file stops compiling
 * until someone writes the French for it. That compile error is the whole point: the
 * alternative is a lookup that silently misses and shows a user the fallback — or worse,
 * the API's English `detail`.
 *
 * <p>These are written for the person reading them, not as translations of the API's
 * `detail`. The API's text is developer-facing ("Refresh token is invalid, expired or has
 * already been used."); the user needs to know what to do ("Votre session a expiré.
 * Reconnectez-vous."). They are deliberately different sentences.
 *
 * <p>Not in an Angular i18n XLIFF file, and that is a considered split rather than an
 * oversight. Angular's i18n is a build-time mechanism for text that appears in templates;
 * this is a runtime lookup keyed on a value that arrives over the network. Putting it
 * through `$localize` would work but would scatter the table across extraction files and
 * lose the exhaustive `Record` type, which is the part earning its keep. When a second
 * locale arrives, this becomes one file per locale selected by `LOCALE_ID` — the shape does
 * not change.
 */
export const ERROR_MESSAGES_FR: Record<ErrorCode, string> = {
  // --- request shape ----------------------------------------------------
  [ErrorCode.VALIDATION_FAILED]: 'Certaines informations sont incorrectes. Vérifiez les champs signalés.',
  [ErrorCode.MALFORMED_REQUEST]: 'La demande n’a pas pu être traitée. Réessayez.',

  // --- registration and sign-in -----------------------------------------
  [ErrorCode.EMAIL_ALREADY_REGISTERED]: 'Un compte existe déjà avec cette adresse e-mail.',
  // Deliberately says nothing about which of the two was wrong — the API does not tell us,
  // by design, and guessing in the interface would undo that.
  [ErrorCode.INVALID_CREDENTIALS]: 'Adresse e-mail ou mot de passe incorrect.',
  [ErrorCode.ACCOUNT_SUSPENDED]: 'Ce compte est suspendu. Contactez le support.',
  [ErrorCode.ACCOUNT_CLOSED]: 'Ce compte a été fermé.',

  // --- tokens -----------------------------------------------------------
  // The user should never see these two: the interceptor renews the session on a 401, and
  // only shows an error if the renewal itself fails. If one does appear, it means the
  // session is genuinely over.
  [ErrorCode.INVALID_TOKEN]: 'Votre session a expiré. Reconnectez-vous.',
  [ErrorCode.INVALID_REFRESH_TOKEN]: 'Votre session a expiré. Reconnectez-vous.',

  // --- passwords --------------------------------------------------------
  [ErrorCode.CURRENT_PASSWORD_INCORRECT]: 'Le mot de passe actuel est incorrect.',
  [ErrorCode.INVALID_RESET_TOKEN]:
    'Ce lien de réinitialisation n’est plus valable. Demandez-en un nouveau.',

  // --- throttling -------------------------------------------------------
  // Both carry retryAfterSeconds; ErrorMessageService appends the wait when it is known,
  // which is why neither sentence names a duration itself.
  [ErrorCode.TOO_MANY_ATTEMPTS]: 'Trop de tentatives de connexion.',
  [ErrorCode.RATE_LIMITED]: 'Trop de requêtes.',

  // --- authorisation and routing ----------------------------------------
  [ErrorCode.FORBIDDEN]: 'Vous n’avez pas accès à cette ressource.',
  [ErrorCode.NOT_FOUND]: 'Cette page ou cette annonce n’existe pas, ou n’est plus disponible.',
  [ErrorCode.METHOD_NOT_ALLOWED]: 'La demande n’a pas pu être traitée. Réessayez.',
  [ErrorCode.UNSUPPORTED_MEDIA_TYPE]: 'Ce format de fichier n’est pas accepté.',
  [ErrorCode.CONFLICT]: 'Cette action n’est pas possible dans l’état actuel.',

  // --- listings ---------------------------------------------------------
  [ErrorCode.ACTIVE_LISTING_LIMIT_REACHED]:
    'Vous avez atteint le nombre maximum d’annonces en ligne. Mettez-en une en pause ou supprimez-la.',
  [ErrorCode.CONCURRENT_MODIFICATION]:
    'Cette annonce a été modifiée entre-temps. Rechargez la page et réessayez.',

  // --- photos -----------------------------------------------------------
  [ErrorCode.UNSUPPORTED_IMAGE_TYPE]: 'Ce format d’image n’est pas accepté. Utilisez un JPEG ou un PNG.',
  [ErrorCode.IMAGE_TOO_LARGE]: 'Cette image est trop volumineuse.',
  [ErrorCode.IMAGE_TOO_SMALL]: 'Cette image est trop petite. Choisissez une photo plus grande.',
  [ErrorCode.TOO_MANY_IMAGES]: 'Cette annonce contient déjà le nombre maximum de photos.',
  [ErrorCode.UPLOAD_NOT_FOUND]: 'L’envoi de cette photo n’a pas abouti. Réessayez.',
  [ErrorCode.PHOTO_REQUIRED]: 'Ajoutez au moins une photo avant de publier.',

  // --- pagination -------------------------------------------------------
  // Only reachable by editing a URL by hand, or by holding a cursor across a sort change.
  [ErrorCode.INVALID_CURSOR]: 'Cette page de résultats n’est plus valable. Relancez la recherche.',

  // --- natural-language search -----------------------------------------
  // The page offers the ordinary search beside this message, so it need not say how.
  [ErrorCode.INTERPRETER_UNAVAILABLE]: 'La recherche intelligente est indisponible pour le moment.',

  // --- the catch-all ----------------------------------------------------
  [ErrorCode.INTERNAL_ERROR]:
    'Une erreur inattendue s’est produite. Réessayez dans un instant.',
};

/**
 * French labels for the request fields the API names in a validation failure.
 *
 * <p>Keyed on the `field` member of `errors[]`, which is the request property path —
 * `email`, `priceAmount`, `professional.vatNumber`. Not exhaustive and cannot be: `field`
 * is a free-form string, so a field added to a request DTO appears here as a cache miss
 * rather than a compile error. {@link fieldLabelFr} falls back to the raw name, which is
 * ugly in the interface and therefore noticed.
 */
export const FIELD_LABELS_FR: Readonly<Record<string, string>> = {
  // account
  email: 'Adresse e-mail',
  password: 'Mot de passe',
  currentPassword: 'Mot de passe actuel',
  newPassword: 'Nouveau mot de passe',
  role: 'Type de compte',
  displayName: 'Nom affiché',
  firstName: 'Prénom',
  lastName: 'Nom',
  phone: 'Téléphone',
  countryCode: 'Pays',
  locale: 'Langue',
  companyName: 'Raison sociale',
  deviceLabel: 'Appareil',
  token: 'Jeton',

  // professional profile
  'professional.companyName': 'Raison sociale',
  'professional.legalForm': 'Forme juridique',
  'professional.registrationNumber': 'SIRET',
  'professional.vatNumber': 'Numéro de TVA',
  'professional.websiteUrl': 'Site web',
  'professional.description': 'Description',
  'professional.addressLine1': 'Adresse',
  'professional.addressLine2': 'Complément d’adresse',
  'professional.contactPhone': 'Téléphone de contact',

  // listing
  title: 'Titre',
  description: 'Description',
  priceAmount: 'Prix',
  currencyCode: 'Devise',
  postalCode: 'Code postal',
  city: 'Ville',
  makeText: 'Marque',
  modelText: 'Modèle',
  versionText: 'Version',
  makeId: 'Marque',
  modelId: 'Modèle',
  trimId: 'Finition',
  firstRegistrationYear: 'Année de première immatriculation',
  firstRegistrationMonth: 'Mois de première immatriculation',
  mileageKm: 'Kilométrage',
  fuelType: 'Carburant',
  gearbox: 'Boîte de vitesses',
  bodyType: 'Carrosserie',
  drivetrain: 'Transmission',
  powerHp: 'Puissance (ch)',
  powerKw: 'Puissance (kW)',
  engineDisplacementCc: 'Cylindrée',
  doors: 'Nombre de portes',
  seats: 'Nombre de places',
  condition: 'État',
  exteriorColor: 'Couleur extérieure',
  colorText: 'Couleur',
  interiorMaterial: 'Intérieur',
  ownerCount: 'Nombre de propriétaires',
  warrantyMonths: 'Garantie (mois)',
  technicalInspectionValidUntil: 'Contrôle technique valable jusqu’au',
  co2EmissionsGKm: 'Émissions de CO₂',
  emissionClass: 'Norme d’émission',
  vin: 'Numéro de série (VIN)',
  featureCodes: 'Équipements',

  // photos
  contentType: 'Type de fichier',
  byteSize: 'Taille du fichier',
  filename: 'Nom du fichier',
};

/**
 * What to say about a field whose value the API rejected.
 *
 * <p><b>This is the weak point of the error layer, and it is a contract limitation rather
 * than a shortcut.</b> The API reports `{field, message}` where `message` is untranslated
 * developer text — "must be a well-formed email address" — and its wording is explicitly
 * not a contract, so it can be neither shown nor matched on. What arrives is therefore
 * "this field is wrong" and nothing about *why*.
 *
 * <p>Which means the real answer is for the client to validate before submitting, with its
 * own French messages, and to treat a server-side validation failure as a backstop: mark
 * the fields, say they are invalid, and let the form's own rules explain. That is what this
 * returns.
 *
 * <p>The better fix belongs in the API: a machine-readable constraint per violation —
 * `{field: "email", constraint: "EMAIL"}` — would let the client say exactly what is wrong
 * in the user's language. Worth raising when forms are built.
 */
export function fieldLabelFr(field: string): string {
  return FIELD_LABELS_FR[field] ?? field;
}
