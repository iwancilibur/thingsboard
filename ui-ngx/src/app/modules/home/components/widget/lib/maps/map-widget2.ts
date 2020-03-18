///
/// Copyright © 2016-2020 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { MapProviders, MapOptions } from './map-models';
import LeafletMap from './leaflet-map';
import {
    openstreetMapSettingsSchema,
    googleMapSettingsSchema,
    imageMapSettingsSchema,
    tencentMapSettingsSchema,
    commonMapSettingsSchema,
    routeMapSettingsSchema,
    markerClusteringSettingsSchema,
    markerClusteringSettingsSchemaLeaflet,
    hereMapSettingsSchema,
    mapProviderSchema
} from './schemes';
import { MapWidgetStaticInterface, MapWidgetInterface } from './map-widget.interface';
import { OpenStreetMap, TencentMap, GoogleMap, HEREMap, ImageMap } from './providers';
import { parseFunction, parseArray, parseData } from '@app/core/utils';
import { initSchema, addToSchema, mergeSchemes, addCondition, addGroupInfo } from '@app/core/schema-utils';
import { AttributeScope, EntityId } from '@app/shared/public-api';
import { forkJoin } from 'rxjs';
import { WidgetContext } from '@app/modules/home/models/widget-component.models';
import { AttributeService } from '@app/core/public-api';

export class MapWidgetController implements MapWidgetInterface {

    constructor(public mapProvider: MapProviders, private drawRoutes, public ctx: WidgetContext, $element) {
        if (this.map) {
            this.map.map.remove();
            delete this.map;
        }

        this.data = ctx.data;
        if (!$element) {
            $element = ctx.$container[0];
        }
        this.settings = this.initSettings(ctx.settings);

        const MapClass = providerSets[this.provider]?.MapClass;
        if (!MapClass) {
            return;
        }
        this.map = new MapClass($element, this.settings);
        this.map.saveMarkerLocation = this.setMarkerLocation;
    }

    map: LeafletMap;
    provider: MapProviders;
    schema;
    data;
    settings;

    public static dataKeySettingsSchema(): Object {
        return {};
    }

    public static getProvidersSchema() {
        return mergeSchemes([mapProviderSchema,
            ...Object.values(providerSets)?.map(
                setting => addCondition(setting?.schema, `model.provider === '${setting.name}'`))]);
    }

    public static settingsSchema(mapProvider, drawRoutes): Object {
        const schema = initSchema();
        addToSchema(schema, this.getProvidersSchema());
        addGroupInfo(schema, 'Map Provider Settings');
        addToSchema(schema, commonMapSettingsSchema);
        addGroupInfo(schema, 'Common Map Settings');

        if (drawRoutes) {
            addToSchema(schema, routeMapSettingsSchema);
            addGroupInfo(schema, 'Route Map Settings');
        } else if (mapProvider !== 'image-map') {
            const clusteringSchema = mergeSchemes([markerClusteringSettingsSchemaLeaflet, markerClusteringSettingsSchema])
            addToSchema(schema, clusteringSchema);
            addGroupInfo(schema, 'Markers Clustering Settings');
        }
        return schema;
    }

    public static actionSources(): Object {
        return {
            markerClick: {
                name: 'widget-action.marker-click',
                multiple: false
            },
            polygonClick: {
                name: 'widget-action.polygon-click',
                multiple: false
            },
            tooltipAction: {
                name: 'widget-action.tooltip-tag-action',
                multiple: true
            }
        };
    }

    onInit() {
    }

    setMarkerLocation = (e) => {
        const s = this.ctx.$injector.get(AttributeService);
        console.log('MapWidgetController -> setMarkerLocation -> s', s, s.saveEntityAttributes)
        const attributeService = this.ctx.$scope.$injector.get(this.ctx.servicesMap.get('attributeService'));
        forkJoin(
            this.data.filter(data => !!e[data.dataKey.name])
                .map(data => {
                    const entityId: EntityId = {
                        entityType: data.datasource.entityType,
                        id: data.datasource.entityId
                    };
                    return attributeService.saveEntityAttributes(
                        entityId,
                        AttributeScope.SHARED_SCOPE,
                        [{
                            key: data.dataKey.name,
                            value: e[data.dataKey.name]
                        }]
                    );
                })).subscribe(res => {
                    console.log('MapWidgetController -> setMarkerLocation -> res', res)
                });
    }

    initSettings(settings: any) {
        const functionParams = ['data', 'dsData', 'dsIndex'];
        this.provider = settings.provider ? settings.provider : this.mapProvider;

        function getDefCenterPosition(position) {
            if (typeof (position) === 'string')
                return position.split(',');
            if (typeof (position) === 'object')
                return position;
            return [0, 0];
        }

        const customOptions = {
            provider: this.provider,
            mapUrl: settings?.mapImageUrl,
            labelFunction: parseFunction(settings.labelFunction, functionParams),
            tooltipFunction: parseFunction(settings.tooltipFunction, functionParams),
            colorFunction: parseFunction(settings.colorFunction, functionParams),
            polygonColorFunction: parseFunction(settings.polygonColorFunction, functionParams),
            markerImageFunction: parseFunction(settings.markerImageFunction, ['data', 'images', 'dsData', 'dsIndex']),
            labelColor: this.ctx.widgetConfig.color,
            tooltipPattern: settings.tooltipPattern ||
                '<b>${entityName}</b><br/><br/><b>Latitude:</b> ${' + settings.latKeyName + ':7}<br/><b>Longitude:</b> ${' + settings.lngKeyName + ':7}',
            defaultCenterPosition: getDefCenterPosition(settings?.defaultCenterPosition),
            useDraggableMarker: true,
            currentImage: (settings.useMarkerImage && settings.markerImage?.length) ? {
                url: settings.markerImage,
                size: settings.markerImageSize || 34
            } : null
        }
        return { ...defaultSettings, ...settings, ...customOptions, }
    }

    update() {
        if (this.drawRoutes)
            this.map.updatePolylines(parseArray(this.data));
        if (this.settings.showPolygon) {
            this.map.updatePolygons(this.data);
        }
        this.map.updateMarkers(parseData(this.data));
    }

    onDataUpdated() {
    }

    onResize() {
        this.map.onResize();// not work
    }

    resize() {
        this.map?.invalidateSize();
        this.map.onResize();
    }

    onDestroy() {
    }
}

export let TbMapWidgetV2: MapWidgetStaticInterface = MapWidgetController;


const providerSets = {
    'openstreet-map': {
        MapClass: OpenStreetMap,
        schema: openstreetMapSettingsSchema,
        name: 'openstreet-map',
    },
    'tencent-map': {
        MapClass: TencentMap,
        schema: tencentMapSettingsSchema,
        name: 'tencent-map'
    },
    'google-map': {
        MapClass: GoogleMap,
        schema: googleMapSettingsSchema,
        name: 'google-map'
    },
    here: {
        MapClass: HEREMap,
        schema: hereMapSettingsSchema,
        name: 'here'
    },
    'image-map': {
        MapClass: ImageMap,
        schema: imageMapSettingsSchema,
        name: 'image-map'
    }
}

const defaultSettings = {
    xPosKeyName: 'xPos',
    yPosKeyName: 'yPos',
    markerOffsetX: 0.5,
    markerOffsetY: 1,
    latKeyName: 'latitude',
    lngKeyName: 'longitude',
    polygonKeyName: 'coordinates',
    showLabel: false,
    label: '${entityName}',
    showTooltip: false,
    useDefaultCenterPosition: false,
    showTooltipAction: 'click',
    autocloseTooltip: false,
    showPolygon: true,
    labelColor: '#000000',
    color: '#FE7569',
    polygonColor: '#0000ff',
    polygonStrokeColor: '#fe0001',
    polygonOpacity: 0.5,
    polygonStrokeOpacity: 1,
    polygonStrokeWeight: 1,
    useLabelFunction: false,
    markerImages: [],
    strokeWeight: 2,
    strokeOpacity: 1.0,
    initCallback: () => { },
    defaultZoomLevel: 8,
    dontFitMapBounds: false,
    disableScrollZooming: false,
    minZoomLevel: 16,
    credentials: '',
    markerClusteringSetting: null,
    draggebleMarker: false
}