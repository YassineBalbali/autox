/// The Messages tab's places: the site's own paths.
abstract final class MessagesLocations {
  static const inbox = '/messages';

  static String conversation(String id) => '/messages/$id';
}
