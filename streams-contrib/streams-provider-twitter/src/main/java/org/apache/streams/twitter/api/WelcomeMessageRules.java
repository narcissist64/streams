package org.apache.streams.twitter.api;

import org.apache.streams.twitter.pojo.WelcomeMessage;
import org.apache.streams.twitter.pojo.WelcomeMessageRule;

import org.apache.juneau.remoteable.Body;
import org.apache.juneau.remoteable.Query;
import org.apache.juneau.remoteable.QueryIfNE;
import org.apache.juneau.remoteable.RemoteMethod;
import org.apache.juneau.remoteable.Remoteable;

/**
 * Interface for /direct_messages/welcome_messages/rules methods.
 *
 * @see <a href="https://dev.twitter.com/rest/reference">https://dev.twitter.com/rest/reference</a>
 */
@Remoteable(path = "https://api.twitter.com/1.1/direct_messages/welcome_messages/rules")
public interface WelcomeMessageRules {

  /**
   * Returns a list of Welcome Message Rules.
   *
   * @return {@link org.apache.streams.twitter.api.WelcomeMessageRulesListResponse}
   * @see <a href="https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/list-welcome-message-rules">https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/list-welcome-message-rules</a>
   *
   */
  @RemoteMethod(httpMethod = "GET", path = "/list.json")
  public WelcomeMessageRulesListResponse listWelcomeMessageRules(@QueryIfNE WelcomeMessageRulesListRequest parameters);

  /**
   * Returns a Welcome Message Rule by the given id.
   *
   * @return {@link org.apache.streams.twitter.pojo.WelcomeMessageRule}
   * @see <a href="https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/get-welcome-message-rule">https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/get-welcome-message-rule</a>
   *
   */
  @RemoteMethod(httpMethod = "GET", path = "/show.json")
  public WelcomeMessageRule showWelcomeMessageRule(@Query("id") Long id);

  /**
   * Creates a new Welcome Message Rule that determines which Welcome Message will be shown in a given conversation. Returns the created rule if successful.
   *
   * Requires a JSON POST body and Content-Type header to be set to application/json. Setting Content-Length may also be required if it is not automatically.
   *
   * Additional rule configurations are forthcoming. For the initial beta release, the most recently created Rule will always take precedence, and the assigned Welcome Message will be displayed in the conversation.
   *
   * @return {@link org.apache.streams.twitter.pojo.WelcomeMessageRule}
   * @see <a href="https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/new-welcome-message-rule">https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/new-welcome-message-rule</a>
   *
   */
  @RemoteMethod(httpMethod = "POST", path = "/new.json")
  public WelcomeMessageRule newWelcomeMessageRule(@Body WelcomeMessageNewRuleRequest body);

  /**
   * Deletes a Welcome Message Rule by the given id.
   *
   * @see <a href="https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/delete-welcome-message-rule">https://developer.twitter.com/en/docs/direct-messages/welcome-messages/api-reference/delete-welcome-message-rule</a>
   *
   */
  @RemoteMethod(httpMethod = "DELETE", path = "/destroy.json")
  public void destroyWelcomeMessageRule(@Query("id") Long id);

}
