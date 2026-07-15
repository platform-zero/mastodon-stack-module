# frozen_string_literal: true

# Ensure OIDC state survives cross-subdomain redirects.
cookie_domain = ENV['SESSION_COOKIE_DOMAIN']
cookie_domain = nil if cookie_domain.nil? || cookie_domain.strip.empty?
same_site = ENV.fetch('SESSION_COOKIE_SAMESITE', 'lax').strip.downcase.to_sym

Rails.application.config.session_store :cookie_store,
                                        key: '_mastodon_session',
                                        secure: true,
                                        same_site: same_site,
                                        domain: cookie_domain
