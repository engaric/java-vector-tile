/*****************************************************************
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 ****************************************************************/
package no.ecc.vectortile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import vector_tile.VectorTile;
import vector_tile.VectorTile.Tile.GeomType;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;

public class VectorTileDecoder {

    private int extent;
    private final Map<String, List<Feature>> featuresByLayerName = new HashMap<String, List<Feature>>();

    public void decode(byte[] data) throws IOException {
        VectorTile.Tile tile = VectorTile.Tile.PARSER.parseFrom(data);
        decode(tile);
    }
    
    public void decode(InputStream in) throws IOException {
        VectorTile.Tile tile = VectorTile.Tile.PARSER.parseFrom(in);
        decode(tile);
    }
    
    private void decode(VectorTile.Tile tile) throws IOException {

        GeometryFactory gf = new GeometryFactory();


        for (VectorTile.Tile.Layer layer : tile.getLayersList()) {

            String layerName = layer.getName();
            extent = layer.getExtent();
            double scale = extent / 256.0;

            List<Feature> features = featuresByLayerName.get(layerName);
            if (features == null) {
                features = new ArrayList<VectorTileDecoder.Feature>();
                featuresByLayerName.put(layerName, features);
            }

            List<String> keys = new ArrayList<String>(layer.getKeysList());
            List<Object> values = new ArrayList<Object>();

            for (VectorTile.Tile.Value value : layer.getValuesList()) {
                if (value.hasBoolValue()) {
                    values.add(value.getBoolValue());
                } else if (value.hasDoubleValue()) {
                    values.add(value.getDoubleValue());
                } else if (value.hasFloatValue()) {
                    values.add(value.getFloatValue());
                } else if (value.hasIntValue()) {
                    values.add(value.getIntValue());
                } else if (value.hasSintValue()) {
                    values.add(value.getSintValue());
                } else if (value.hasUintValue()) {
                    values.add(value.getUintValue());
                } else if (value.hasStringValue()) {
                    values.add(value.getStringValue());
                } else {
                    values.add(null);
                }
            }

            for (VectorTile.Tile.Feature feature : layer.getFeaturesList()) {

                int tagsCount = feature.getTagsCount();
                Map<String, Object> attributes = new HashMap<String, Object>(tagsCount / 2);
                for (Iterator<Integer> it = feature.getTagsList().iterator(); it.hasNext();) {
                    String key = keys.get(it.next());
                    Object value = values.get(it.next());
                    attributes.put(key, value);
                }

                int x = 0;
                int y = 0;

                List<List<Coordinate>> coordsList = new ArrayList<List<Coordinate>>();
                List<Coordinate> coords = null;

                int geometryCount = feature.getGeometryCount();
                int length = 0;
                int command = 0;
                int i = 0;
                while (i < geometryCount) {

                    if (length <= 0) {
                        length = feature.getGeometry(i++);
                        command = length & ((1 << 3) - 1);
                        length = length >> 3;
                    }

                    if (length > 0) {

                        if (command == Command.MoveTo) {
                            coords = new ArrayList<Coordinate>();
                            coordsList.add(coords);
                        }

                        if (command == Command.ClosePath) {
                            if (feature.getType() != GeomType.POINT && !coords.isEmpty()) {
                                coords.add(coords.get(0));
                            }
                            length--;
                            continue;
                        }

                        int dx = feature.getGeometry(i++);
                        int dy = feature.getGeometry(i++);

                        length--;

                        dx = zigZagDecode(dx);
                        dy = zigZagDecode(dy);

                        x = x + dx;
                        y = y + dy;

                        Coordinate coord = new Coordinate(x / scale, y / scale);
                        coords.add(coord);
                    }

                }

                Geometry geometry = null;

                switch (feature.getType()) {
                case LINESTRING:
                    List<LineString> lineStrings = new ArrayList<LineString>();
                    for (List<Coordinate> cs : coordsList) {
                        lineStrings.add(gf.createLineString(cs.toArray(new Coordinate[cs.size()])));
                    }
                    if (lineStrings.size() == 1) {
                        geometry = lineStrings.get(0);
                    } else if (lineStrings.size() > 1) {
                        geometry = gf.createMultiLineString(lineStrings.toArray(new LineString[lineStrings.size()]));
                    }
                    break;
                case POINT:
                    List<Coordinate> allCoords = new ArrayList<Coordinate>();
                    for (List<Coordinate> cs : coordsList) {
                        allCoords.addAll(cs);
                    }
                    if (allCoords.size() == 1) {
                        geometry = gf.createPoint(allCoords.get(0));
                    } else if (allCoords.size() > 1) {
                        geometry = gf.createMultiPoint(allCoords.toArray(new Coordinate[allCoords.size()]));
                    }
                    break;
                case POLYGON:
                    List<LinearRing> rings = new ArrayList<LinearRing>();
                    for (List<Coordinate> cs : coordsList) {
                        rings.add(gf.createLinearRing(cs.toArray(new Coordinate[cs.size()])));
                    }
                    if (rings.size() > 0) {
                        LinearRing shell = rings.get(0);
                        LinearRing[] holes = rings.subList(1, rings.size()).toArray(new LinearRing[rings.size() - 1]);
                        geometry = gf.createPolygon(shell, holes);
                    }
                    break;
                case UNKNOWN:
                    break;
                default:
                    break;
                }

                if (geometry == null) {
                    geometry = gf.createGeometryCollection(new Geometry[0]);
                }

                features.add(new Feature(geometry, Collections.unmodifiableMap(attributes)));

            }

        }

    }

    public Set<String> getLayerNames() {
        return Collections.unmodifiableSet(featuresByLayerName.keySet());
    }

    public List<Feature> getFeatures(String layerName) {
        List<Feature> features = featuresByLayerName.get(layerName);
        if (features == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(features);
    }
    
    int getExtent() {
        return extent;
    }

    static int zigZagDecode(int n) {
        return ((n >> 1) ^ (-(n & 1)));
    }

    public static final class Feature {

        private final Geometry geometry;
        private final Map<String, Object> attributes;

        public Feature(Geometry geometry, Map<String, Object> attributes) {
            this.geometry = geometry;
            this.attributes = attributes;
        }

        public Geometry getGeometry() {
            return geometry;
        }

        public Map<String, Object> getAttributes() {
            return attributes;
        }

    }

}
