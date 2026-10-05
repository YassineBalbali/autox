-- AutoX V5 — seed data for platform-owned reference tables.
-- Vehicle makes/models/trims and the geo gazetteer are bulk datasets and are
-- deliberately NOT seeded here; see docs/schema-decisions.md.

INSERT INTO currency (code, minor_unit, symbol) VALUES
    ('EUR', 2, '€'),
    ('CHF', 2, 'CHF'),
    ('GBP', 2, '£'),
    ('PLN', 2, 'zł'),
    ('SEK', 2, 'kr'),
    ('DKK', 2, 'kr');

INSERT INTO country (code, name, default_currency_code, default_locale, phone_prefix, distance_unit, enabled) VALUES
    ('FR', 'France',       'EUR', 'fr-FR', '+33',  'KM', TRUE),
    ('BE', 'Belgique',     'EUR', 'fr-BE', '+32',  'KM', FALSE),
    ('LU', 'Luxembourg',   'EUR', 'fr-LU', '+352', 'KM', FALSE),
    ('DE', 'Deutschland',  'EUR', 'de-DE', '+49',  'KM', FALSE),
    ('ES', 'España',       'EUR', 'es-ES', '+34',  'KM', FALSE),
    ('IT', 'Italia',       'EUR', 'it-IT', '+39',  'KM', FALSE),
    ('NL', 'Nederland',    'EUR', 'nl-NL', '+31',  'KM', FALSE),
    ('CH', 'Schweiz',      'CHF', 'de-CH', '+41',  'KM', FALSE);

-- Equipment vocabulary, with fr-FR and en labels.
INSERT INTO vehicle_feature (id, code, category, display_order) VALUES
    (gen_random_uuid(), 'AIR_CONDITIONING',      'COMFORT',    10),
    (gen_random_uuid(), 'CLIMATE_CONTROL_AUTO',  'COMFORT',    20),
    (gen_random_uuid(), 'HEATED_SEATS',          'COMFORT',    30),
    (gen_random_uuid(), 'ELECTRIC_SEATS',        'COMFORT',    40),
    (gen_random_uuid(), 'KEYLESS_ENTRY',         'COMFORT',    50),
    (gen_random_uuid(), 'ELECTRIC_WINDOWS',      'COMFORT',    60),
    (gen_random_uuid(), 'CRUISE_CONTROL',        'ASSISTANCE', 70),
    (gen_random_uuid(), 'ADAPTIVE_CRUISE',       'ASSISTANCE', 80),
    (gen_random_uuid(), 'PARKING_SENSORS_REAR',  'ASSISTANCE', 90),
    (gen_random_uuid(), 'PARKING_SENSORS_FRONT', 'ASSISTANCE', 100),
    (gen_random_uuid(), 'REVERSING_CAMERA',      'ASSISTANCE', 110),
    (gen_random_uuid(), 'LANE_ASSIST',           'ASSISTANCE', 120),
    (gen_random_uuid(), 'BLIND_SPOT_MONITOR',    'ASSISTANCE', 130),
    (gen_random_uuid(), 'ABS',                   'SAFETY',     140),
    (gen_random_uuid(), 'ESP',                   'SAFETY',     150),
    (gen_random_uuid(), 'EMERGENCY_BRAKING',     'SAFETY',     160),
    (gen_random_uuid(), 'ISOFIX',                'SAFETY',     170),
    (gen_random_uuid(), 'TYRE_PRESSURE_MONITOR', 'SAFETY',     180),
    (gen_random_uuid(), 'BLUETOOTH',             'MULTIMEDIA', 190),
    (gen_random_uuid(), 'APPLE_CARPLAY',         'MULTIMEDIA', 200),
    (gen_random_uuid(), 'ANDROID_AUTO',          'MULTIMEDIA', 210),
    (gen_random_uuid(), 'NAVIGATION',            'MULTIMEDIA', 220),
    (gen_random_uuid(), 'DAB_RADIO',             'MULTIMEDIA', 230),
    (gen_random_uuid(), 'ALLOY_WHEELS',          'EXTERIOR',   240),
    (gen_random_uuid(), 'LED_HEADLIGHTS',        'EXTERIOR',   250),
    (gen_random_uuid(), 'XENON_HEADLIGHTS',      'EXTERIOR',   260),
    (gen_random_uuid(), 'PANORAMIC_ROOF',        'EXTERIOR',   270),
    (gen_random_uuid(), 'SUNROOF',               'EXTERIOR',   280),
    (gen_random_uuid(), 'TOW_BAR',               'EXTERIOR',   290),
    (gen_random_uuid(), 'ROOF_RAILS',            'EXTERIOR',   300),
    (gen_random_uuid(), 'LEATHER_STEERING',      'INTERIOR',   310),
    (gen_random_uuid(), 'AMBIENT_LIGHTING',      'INTERIOR',   320),
    (gen_random_uuid(), 'START_STOP',            'OTHER',      330),
    (gen_random_uuid(), 'PARTICLE_FILTER',       'OTHER',      340);

INSERT INTO vehicle_feature_translation (feature_id, locale, label)
SELECT f.id, t.locale, t.label
FROM vehicle_feature f
JOIN (VALUES
    ('AIR_CONDITIONING',      'fr-FR', 'Climatisation'),              ('AIR_CONDITIONING',      'en', 'Air conditioning'),
    ('CLIMATE_CONTROL_AUTO',  'fr-FR', 'Climatisation automatique'),  ('CLIMATE_CONTROL_AUTO',  'en', 'Automatic climate control'),
    ('HEATED_SEATS',          'fr-FR', 'Sièges chauffants'),          ('HEATED_SEATS',          'en', 'Heated seats'),
    ('ELECTRIC_SEATS',        'fr-FR', 'Sièges électriques'),         ('ELECTRIC_SEATS',        'en', 'Electric seats'),
    ('KEYLESS_ENTRY',         'fr-FR', 'Accès sans clé'),             ('KEYLESS_ENTRY',         'en', 'Keyless entry'),
    ('ELECTRIC_WINDOWS',      'fr-FR', 'Vitres électriques'),         ('ELECTRIC_WINDOWS',      'en', 'Electric windows'),
    ('CRUISE_CONTROL',        'fr-FR', 'Régulateur de vitesse'),      ('CRUISE_CONTROL',        'en', 'Cruise control'),
    ('ADAPTIVE_CRUISE',       'fr-FR', 'Régulateur adaptatif'),       ('ADAPTIVE_CRUISE',       'en', 'Adaptive cruise control'),
    ('PARKING_SENSORS_REAR',  'fr-FR', 'Radar de recul'),             ('PARKING_SENSORS_REAR',  'en', 'Rear parking sensors'),
    ('PARKING_SENSORS_FRONT', 'fr-FR', 'Radar avant'),                ('PARKING_SENSORS_FRONT', 'en', 'Front parking sensors'),
    ('REVERSING_CAMERA',      'fr-FR', 'Caméra de recul'),            ('REVERSING_CAMERA',      'en', 'Reversing camera'),
    ('LANE_ASSIST',           'fr-FR', 'Maintien de voie'),           ('LANE_ASSIST',           'en', 'Lane keep assist'),
    ('BLIND_SPOT_MONITOR',    'fr-FR', 'Détecteur d''angle mort'),    ('BLIND_SPOT_MONITOR',    'en', 'Blind spot monitor'),
    ('ABS',                   'fr-FR', 'ABS'),                        ('ABS',                   'en', 'ABS'),
    ('ESP',                   'fr-FR', 'ESP'),                        ('ESP',                   'en', 'ESP'),
    ('EMERGENCY_BRAKING',     'fr-FR', 'Freinage d''urgence'),        ('EMERGENCY_BRAKING',     'en', 'Emergency braking'),
    ('ISOFIX',                'fr-FR', 'Fixations Isofix'),           ('ISOFIX',                'en', 'Isofix'),
    ('TYRE_PRESSURE_MONITOR', 'fr-FR', 'Contrôle de pression'),       ('TYRE_PRESSURE_MONITOR', 'en', 'Tyre pressure monitor'),
    ('BLUETOOTH',             'fr-FR', 'Bluetooth'),                  ('BLUETOOTH',             'en', 'Bluetooth'),
    ('APPLE_CARPLAY',         'fr-FR', 'Apple CarPlay'),              ('APPLE_CARPLAY',         'en', 'Apple CarPlay'),
    ('ANDROID_AUTO',          'fr-FR', 'Android Auto'),               ('ANDROID_AUTO',          'en', 'Android Auto'),
    ('NAVIGATION',            'fr-FR', 'GPS / Navigation'),           ('NAVIGATION',            'en', 'Navigation'),
    ('DAB_RADIO',             'fr-FR', 'Radio DAB'),                  ('DAB_RADIO',             'en', 'DAB radio'),
    ('ALLOY_WHEELS',          'fr-FR', 'Jantes alliage'),             ('ALLOY_WHEELS',          'en', 'Alloy wheels'),
    ('LED_HEADLIGHTS',        'fr-FR', 'Phares LED'),                 ('LED_HEADLIGHTS',        'en', 'LED headlights'),
    ('XENON_HEADLIGHTS',      'fr-FR', 'Phares xénon'),               ('XENON_HEADLIGHTS',      'en', 'Xenon headlights'),
    ('PANORAMIC_ROOF',        'fr-FR', 'Toit panoramique'),           ('PANORAMIC_ROOF',        'en', 'Panoramic roof'),
    ('SUNROOF',               'fr-FR', 'Toit ouvrant'),               ('SUNROOF',               'en', 'Sunroof'),
    ('TOW_BAR',               'fr-FR', 'Attelage'),                   ('TOW_BAR',               'en', 'Tow bar'),
    ('ROOF_RAILS',            'fr-FR', 'Barres de toit'),             ('ROOF_RAILS',            'en', 'Roof rails'),
    ('LEATHER_STEERING',      'fr-FR', 'Volant cuir'),                ('LEATHER_STEERING',      'en', 'Leather steering wheel'),
    ('AMBIENT_LIGHTING',      'fr-FR', 'Éclairage d''ambiance'),      ('AMBIENT_LIGHTING',      'en', 'Ambient lighting'),
    ('START_STOP',            'fr-FR', 'Start & Stop'),               ('START_STOP',            'en', 'Start & Stop'),
    ('PARTICLE_FILTER',       'fr-FR', 'Filtre à particules'),        ('PARTICLE_FILTER',       'en', 'Particle filter')
) AS t (code, locale, label) ON t.code = f.code;
