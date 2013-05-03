/*******************************************************************************
 * Copyright 2012 The MITRE Corporation
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.mitre.openid.connect.token;

import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import org.mitre.openid.connect.model.ApprovedSite;
import org.mitre.openid.connect.model.WhitelistedSite;
import org.mitre.openid.connect.service.ApprovedSiteService;
import org.mitre.openid.connect.service.WhitelistedSiteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.approval.UserApprovalHandler;
import org.springframework.stereotype.Component;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;

/**
 * Custom User Approval Handler implementation which uses a concept of a whitelist, 
 * blacklist, and greylist. 
 * 
 * Blacklisted sites will be caught and handled before this 
 * point. 
 * 
 * Whitelisted sites will be automatically approved, and an ApprovedSite entry will
 * be created for the site the first time a given user access it. 
 * 
 * All other sites fall into the greylist - the user will be presented with the user
 * approval page upon their first visit
 * @author aanganes
 *
 */
@Component("tofuUserApprovalHandler")
public class TofuUserApprovalHandler implements UserApprovalHandler {
	
	@Autowired
	private ApprovedSiteService approvedSiteService;
	
	@Autowired
	private WhitelistedSiteService whitelistedSiteService;
	
	@Autowired
	private ClientDetailsService clientDetailsService;
	
	

	@Override
	public boolean isApproved(OAuth2Request oAuthRequest, Authentication userAuthentication) {
		
		// if this request is already approved, pass that info through
		// (this flag may be set by updateBeforeApproval, which can also do funny things with scopes, etc)
		if (oAuthRequest.isApproved()) {
			return true;
		} else {
			// if not, check to see if the user has approved it
			
			// TODO: make parameter name configurable?
			boolean approved = Boolean.parseBoolean(oAuthRequest.getApprovalParameters().get("user_oauth_approval"));
	
			return userAuthentication.isAuthenticated() && approved;
		}

	}

	/**
	 * Check if the user has already stored a positive approval decision for this site; or if the
	 * site is whitelisted, approve it automatically.
	 * 
	 * Otherwise the user will be directed to the approval page and can make their own decision.
	 * 
	 * @param oAuthRequest	the incoming authorization request	
	 * @param userAuthentication	the Principal representing the currently-logged-in user
	 * 
	 * @return 						the updated AuthorizationRequest
	 */
	@Override
	public OAuth2Request checkForPreApproval(OAuth2Request oAuthRequest, Authentication userAuthentication) {
		
		//First, check database to see if the user identified by the userAuthentication has stored an approval decision
		
		//getName may not be filled in? TODO: investigate
		String userId = userAuthentication.getName();
		String clientId = oAuthRequest.getClientId();

		//lookup ApprovedSites by userId and clientId
		boolean alreadyApproved = false;
		Collection<ApprovedSite> aps = approvedSiteService.getByClientIdAndUserId(clientId, userId);
		for (ApprovedSite ap : aps) {
			
			if (!ap.isExpired()) {
			
				// if we find one that fits...
				if (scopesMatch(oAuthRequest.getScope(), ap.getAllowedScopes())) {
					
					//We have a match; update the access date on the AP entry and return true.
					ap.setAccessDate(new Date());
					approvedSiteService.save(ap);
	
					oAuthRequest.getExtensionProperties().put("approved_site", ap.getId());
					oAuthRequest.setApproved(true);					
					alreadyApproved = true;
				}
			}
        }
		
		if (!alreadyApproved) {
			WhitelistedSite ws = whitelistedSiteService.getByClientId(clientId);
			if (ws != null && scopesMatch(oAuthRequest.getScope(), ws.getAllowedScopes())) {
				
				//Create an approved site
				ApprovedSite newSite = approvedSiteService.createApprovedSite(clientId, userId, null, ws.getAllowedScopes(), ws);	
				oAuthRequest.getExtensionProperties().put("approved_site", newSite.getId());
				oAuthRequest.setApproved(true);
			}
		}
		
		return oAuthRequest;
		
	}
	

	@Override
    public OAuth2Request updateAfterApproval(OAuth2Request oAuthRequest, Authentication userAuthentication) {

		String userId = userAuthentication.getName();
		String clientId = oAuthRequest.getClientId();
		ClientDetails client = clientDetailsService.loadClientByClientId(clientId);
		
		// This must be re-parsed here because SECOAUTH forces us to call things in a strange order
		boolean approved = Boolean.parseBoolean(oAuthRequest.getApprovalParameters().get("user_oauth_approval"));
		
		if (approved) {
			
			oAuthRequest.setApproved(true);
			
			// process scopes from user input
			Set<String> allowedScopes = Sets.newHashSet();
			Map<String,String> approvalParams = oAuthRequest.getApprovalParameters();
			
			Set<String> keys = approvalParams.keySet();
			
			for (String key : keys) {
				if (key.startsWith("scope_")) {
					//This is a scope parameter from the approval page. The value sent back should
					//be the scope string. Check to make sure it is contained in the client's 
					//registered allowed scopes.
					
					String scope = approvalParams.get(key);
					
					//Make sure this scope is allowed for the given client
					if (client.getScope().contains(scope)) {
						allowedScopes.add(scope);
					}
				}
			}

			// inject the user-allowed scopes into the auth request
			// TODO: for the moment this allows both upscoping and downscoping.
			oAuthRequest.setScope(allowedScopes);
			
			//Only store an ApprovedSite if the user has checked "remember this decision":
			String remember = oAuthRequest.getApprovalParameters().get("remember");
			if (!Strings.isNullOrEmpty(remember) && !remember.equals("none")) {
				
				Date timeout = null;
				if (remember.equals("one-hour")) {
					// set the timeout to one hour from now
					Calendar cal = Calendar.getInstance();
					cal.add(Calendar.HOUR, 1);
					timeout = cal.getTime();
				}
				
				ApprovedSite newSite = approvedSiteService.createApprovedSite(clientId, userId, timeout, allowedScopes, null);
				oAuthRequest.getExtensionProperties().put("approved_site", newSite.getId());
			}
			
		}

		return oAuthRequest;
    }
	
	/**
	 * Check whether the requested scope set is a proper subset of the allowed scopes.
	 * 
	 * @param requestedScopes
	 * @param allowedScopes
	 * @return
	 */
	private boolean scopesMatch(Set<String> requestedScopes, Set<String> allowedScopes) {
		
		for (String scope : requestedScopes) {
			
			if (!allowedScopes.contains(scope)) {
				return false; //throw new InvalidScopeException("Invalid scope: " + scope, allowedScopes);
			}
		}
		
		return true;
	}
	
}
