/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.core.io.rest.core.internal.profile;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.auth.Role;
import org.openhab.core.io.rest.LocaleService;
import org.openhab.core.io.rest.RESTConstants;
import org.openhab.core.io.rest.RESTResource;
import org.openhab.core.io.rest.Stream2JSONInputStream;
import org.openhab.core.items.ItemUtil;
import org.openhab.core.thing.profiles.ProfileType;
import org.openhab.core.thing.profiles.ProfileTypeRegistry;
import org.openhab.core.thing.profiles.StateProfileType;
import org.openhab.core.thing.profiles.TriggerProfileType;
import org.openhab.core.thing.profiles.dto.ProfileTypeDTO;
import org.openhab.core.thing.profiles.dto.ProfileTypeDTOMapper;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.thing.type.ChannelTypeRegistry;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.jaxrs.whiteboard.JaxrsWhiteboardConstants;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JSONRequired;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsApplicationSelect;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsName;
import org.osgi.service.jaxrs.whiteboard.propertytypes.JaxrsResource;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.AuthorizationScope;

/**
 * REST resource to obtain profile-types
 *
 * @author Stefan Triller - Initial contribution
 * @author Markus Rathgeb - Migrated to JAX-RS Whiteboard Specification
 */
@Component
@JaxrsResource
@JaxrsName(ProfileTypeResource.PATH_PROFILE_TYPES)
@JaxrsApplicationSelect("(" + JaxrsWhiteboardConstants.JAX_RS_NAME + "=" + RESTConstants.JAX_RS_NAME + ")")
@JSONRequired
@Path(ProfileTypeResource.PATH_PROFILE_TYPES)
@RolesAllowed({ Role.ADMIN })
@Api(value = ProfileTypeResource.PATH_PROFILE_TYPES, authorizations = { @Authorization(value = "oauth2", scopes = {
        @AuthorizationScope(scope = "admin", description = "Admin operations") }) })
@NonNullByDefault
public class ProfileTypeResource implements RESTResource {

    /** The URI path to this resource */
    public static final String PATH_PROFILE_TYPES = "profile-types";

    private final ChannelTypeRegistry channelTypeRegistry;
    private final LocaleService localeService;
    private final ProfileTypeRegistry profileTypeRegistry;

    @Activate
    public ProfileTypeResource( //
            final @Reference ChannelTypeRegistry channelTypeRegistry, //
            final @Reference LocaleService localeService, //
            final @Reference ProfileTypeRegistry profileTypeRegistry) {
        this.channelTypeRegistry = channelTypeRegistry;
        this.localeService = localeService;
        this.profileTypeRegistry = profileTypeRegistry;
    }

    @GET
    @RolesAllowed({ Role.USER })
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets all available profile types.", response = ProfileTypeDTO.class, responseContainer = "Set")
    @ApiResponses(value = @ApiResponse(code = 200, message = "OK", response = ProfileTypeDTO.class, responseContainer = "Set"))
    public Response getAll(
            @HeaderParam(HttpHeaders.ACCEPT_LANGUAGE) @ApiParam(value = "language") @Nullable String language,
            @QueryParam("channelTypeUID") @ApiParam(value = "channel type filter") @Nullable String channelTypeUID,
            @QueryParam("itemType") @ApiParam(value = "item type filter") @Nullable String itemType) {
        Locale locale = localeService.getLocale(language);
        return Response.ok(new Stream2JSONInputStream(getProfileTypes(locale, channelTypeUID, itemType))).build();
    }

    protected Stream<ProfileTypeDTO> getProfileTypes(@Nullable Locale locale, @Nullable String channelTypeUID,
            @Nullable String itemType) {
        return profileTypeRegistry.getProfileTypes(locale).stream().filter(matchesChannelUID(channelTypeUID, locale))
                .filter(matchesItemType(itemType)).map(profileType -> ProfileTypeDTOMapper.map(profileType));
    }

    private Predicate<ProfileType> matchesChannelUID(@Nullable String channelTypeUID, @Nullable Locale locale) {
        if (channelTypeUID == null) {
            return t -> true;
        }
        ChannelType channelType = channelTypeRegistry.getChannelType(new ChannelTypeUID(channelTypeUID), locale);
        if (channelType == null) {
            // requested to filter against an unknown channel type -> do not return a ProfileType
            return t -> false;
        }
        switch (channelType.getKind()) {
            case STATE:
                return t -> stateProfileMatchesProfileType(t, channelType);
            case TRIGGER:
                return t -> triggerProfileMatchesProfileType(t, channelType);
        }
        return t -> false;
    }

    private Predicate<ProfileType> matchesItemType(@Nullable String itemType) {
        if (itemType == null) {
            return t -> true;
        }
        return t -> profileTypeMatchesItemType(t, itemType);
    }

    private boolean profileTypeMatchesItemType(ProfileType pt, String itemType) {
        Collection<String> supportedItemTypesOnProfileType = pt.getSupportedItemTypes();
        if (supportedItemTypesOnProfileType.isEmpty()
                || supportedItemTypesOnProfileType.contains(ItemUtil.getMainItemType(itemType))
                || supportedItemTypesOnProfileType.contains(itemType)) {
            return true;
        }
        return false;
    }

    private boolean triggerProfileMatchesProfileType(ProfileType profileType, ChannelType channelType) {
        if (profileType instanceof TriggerProfileType) {
            TriggerProfileType triggerProfileType = (TriggerProfileType) profileType;

            if (triggerProfileType.getSupportedChannelTypeUIDs().isEmpty()) {
                return true;
            }

            if (triggerProfileType.getSupportedChannelTypeUIDs().contains(channelType.getUID())) {
                return true;
            }
        }
        return false;
    }

    private boolean stateProfileMatchesProfileType(ProfileType profileType, ChannelType channelType) {
        if (profileType instanceof StateProfileType) {
            StateProfileType stateProfileType = (StateProfileType) profileType;

            if (stateProfileType.getSupportedItemTypesOfChannel().isEmpty()) {
                return true;
            }

            Collection<String> supportedItemTypesOfChannelOnProfileType = stateProfileType
                    .getSupportedItemTypesOfChannel();
            if (supportedItemTypesOfChannelOnProfileType.contains(ItemUtil.getMainItemType(channelType.getItemType()))
                    || supportedItemTypesOfChannelOnProfileType.contains(channelType.getItemType())) {
                return true;
            }
        }
        return false;
    }
}
