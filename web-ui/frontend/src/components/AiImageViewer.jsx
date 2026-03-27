import React, { useEffect, useMemo, useRef, useState } from 'react';

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

const toPoint = (rawPoint) => {
    if (!rawPoint) return null;
    if (Array.isArray(rawPoint) && rawPoint.length >= 2) {
        const x = Number(rawPoint[0]);
        const y = Number(rawPoint[1]);
        return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
    }
    if (typeof rawPoint === 'object') {
        const x = Number(rawPoint.x);
        const y = Number(rawPoint.y);
        return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null;
    }
    return null;
};

const toPoints = (rawList) => {
    if (!Array.isArray(rawList)) return [];
    return rawList.map(toPoint).filter(Boolean);
};

const polylinePath = (points) => {
    if (points.length < 2) return '';
    return points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
};

const safeZoneBandPath = (nervePath, safeZonePath) => {
    if (nervePath.length < 2 || safeZonePath.length < 2) return '';
    const count = Math.min(nervePath.length, safeZonePath.length);
    const upper = safeZonePath.slice(0, count);
    const lower = nervePath.slice(0, count);

    const topLine = upper.map((point, index) => `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`).join(' ');
    const bottomLine = lower
        .slice()
        .reverse()
        .map((point) => `L ${point.x} ${point.y}`)
        .join(' ');

    return `${topLine} ${bottomLine} Z`;
};

const boundsFromAllPoints = (lists) => {
    const points = lists.flat();
    if (!points.length) {
        return { width: 512, height: 512 };
    }

    const maxX = Math.max(0, ...points.map((point) => point.x));
    const maxY = Math.max(0, ...points.map((point) => point.y));

    return {
        width: Math.max(256, Math.ceil(maxX + 24)),
        height: Math.max(256, Math.ceil(maxY + 24))
    };
};

export default function AiImageViewer({ analysisData }) {
    const containerRef = useRef(null);
    const dragRef = useRef(null);

    const [containerSize, setContainerSize] = useState({ width: 1, height: 1 });
    const [imageNaturalSize, setImageNaturalSize] = useState({ width: 0, height: 0 });
    const [viewport, setViewport] = useState({ scale: 1, offsetX: 0, offsetY: 0 });

    const planningOverlay = analysisData?.planning_overlay_data || {};
    const overlayOuterContour = useMemo(() => toPoints(planningOverlay.outer_contour), [planningOverlay]);
    const overlayInnerContour = useMemo(() => toPoints(planningOverlay.inner_contour), [planningOverlay]);
    const overlayBaseGuide = useMemo(() => toPoints(planningOverlay.base_guide), [planningOverlay]);
    const overlayWidthIndicator = planningOverlay?.width_indicator || null;
    const overlaySectorLines = Array.isArray(planningOverlay?.sector_lines) ? planningOverlay.sector_lines : [];

    const archCurvePoints = useMemo(() => toPoints(analysisData?.arch_curve_data), [analysisData]);
    const nervePathPoints = useMemo(() => toPoints(analysisData?.nerve_path_data), [analysisData]);
    const safeZonePathPoints = useMemo(() => toPoints(analysisData?.safe_zone_path_data), [analysisData]);

    const fallbackBounds = useMemo(() => {
        return boundsFromAllPoints([
            archCurvePoints,
            nervePathPoints,
            safeZonePathPoints,
            overlayOuterContour,
            overlayInnerContour,
            overlayBaseGuide
        ]);
    }, [
        archCurvePoints,
        nervePathPoints,
        safeZonePathPoints,
        overlayOuterContour,
        overlayInnerContour,
        overlayBaseGuide
    ]);

    const imageWidth = imageNaturalSize.width || fallbackBounds.width;
    const imageHeight = imageNaturalSize.height || fallbackBounds.height;

    const fitScale = Math.min(
        containerSize.width / Math.max(1, imageWidth),
        containerSize.height / Math.max(1, imageHeight)
    );

    const drawWidth = imageWidth * fitScale;
    const drawHeight = imageHeight * fitScale;
    const baseLeft = (containerSize.width - drawWidth) / 2;
    const baseTop = (containerSize.height - drawHeight) / 2;

    useEffect(() => {
        const element = containerRef.current;
        if (!element) return undefined;

        const observer = new ResizeObserver((entries) => {
            const entry = entries[0];
            if (!entry) return;
            setContainerSize({
                width: Math.max(1, entry.contentRect.width),
                height: Math.max(1, entry.contentRect.height)
            });
        });

        observer.observe(element);
        return () => observer.disconnect();
    }, []);

    useEffect(() => {
        setViewport({ scale: 1, offsetX: 0, offsetY: 0 });
    }, [analysisData?.id, analysisData?.created_at, analysisData?.opg_image_base64]);

    const imageSource = analysisData?.opg_image_base64
        ? `data:image/png;base64,${analysisData.opg_image_base64}`
        : null;

    const handleWheel = (event) => {
        event.preventDefault();
        const element = containerRef.current;
        if (!element) return;

        const rect = element.getBoundingClientRect();
        const pointerX = event.clientX - rect.left;
        const pointerY = event.clientY - rect.top;

        setViewport((current) => {
            const nextScale = clamp(current.scale * (event.deltaY < 0 ? 1.1 : 0.9), 1, 8);
            if (nextScale === current.scale) return current;

            const scaleFactor = nextScale / current.scale;
            const relativeX = pointerX - baseLeft;
            const relativeY = pointerY - baseTop;

            return {
                scale: nextScale,
                offsetX: relativeX - (relativeX - current.offsetX) * scaleFactor,
                offsetY: relativeY - (relativeY - current.offsetY) * scaleFactor
            };
        });
    };

    const handlePointerDown = (event) => {
        event.preventDefault();
        dragRef.current = {
            pointerId: event.pointerId,
            x: event.clientX,
            y: event.clientY
        };
        event.currentTarget.setPointerCapture(event.pointerId);
    };

    const handlePointerMove = (event) => {
        const drag = dragRef.current;
        if (!drag || drag.pointerId !== event.pointerId) return;

        const dx = event.clientX - drag.x;
        const dy = event.clientY - drag.y;
        dragRef.current = { ...drag, x: event.clientX, y: event.clientY };

        setViewport((current) => ({
            ...current,
            offsetX: current.offsetX + dx,
            offsetY: current.offsetY + dy
        }));
    };

    const handlePointerUp = (event) => {
        const drag = dragRef.current;
        if (!drag || drag.pointerId !== event.pointerId) return;
        dragRef.current = null;
        if (event.currentTarget.hasPointerCapture(event.pointerId)) {
            event.currentTarget.releasePointerCapture(event.pointerId);
        }
    };

    const handleDoubleClick = () => {
        setViewport({ scale: 1, offsetX: 0, offsetY: 0 });
    };

    const widthLineStart = toPoint(overlayWidthIndicator?.start);
    const widthLineEnd = toPoint(overlayWidthIndicator?.end);

    return (
        <div
            ref={containerRef}
            style={{
                position: 'relative',
                width: '100%',
                height: '100%',
                overflow: 'hidden',
                background: '#000',
                touchAction: 'none',
                cursor: dragRef.current ? 'grabbing' : 'grab'
            }}
            onWheel={handleWheel}
            onPointerDown={handlePointerDown}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerUp}
            onPointerCancel={handlePointerUp}
            onPointerLeave={handlePointerUp}
            onDoubleClick={handleDoubleClick}
        >
            <div
                style={{
                    position: 'absolute',
                    left: `${baseLeft + viewport.offsetX}px`,
                    top: `${baseTop + viewport.offsetY}px`,
                    width: `${drawWidth}px`,
                    height: `${drawHeight}px`,
                    transform: `scale(${viewport.scale})`,
                    transformOrigin: '0 0'
                }}
            >
                {imageSource ? (
                    <img
                        src={imageSource}
                        alt="Processed OPG"
                        draggable={false}
                        onLoad={(event) => {
                            setImageNaturalSize({
                                width: event.currentTarget.naturalWidth,
                                height: event.currentTarget.naturalHeight
                            });
                        }}
                        style={{
                            width: '100%',
                            height: '100%',
                            objectFit: 'fill',
                            userSelect: 'none',
                            pointerEvents: 'none'
                        }}
                    />
                ) : (
                    <div
                        style={{
                            width: '100%',
                            height: '100%',
                            background: '#000',
                            color: '#94A3B8',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            fontSize: '0.9rem'
                        }}
                    >
                        No image available
                    </div>
                )}

                <svg
                    width="100%"
                    height="100%"
                    viewBox={`0 0 ${imageWidth} ${imageHeight}`}
                    preserveAspectRatio="none"
                    style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
                >
                    {safeZoneBandPath(nervePathPoints, safeZonePathPoints) && (
                        <path
                            d={safeZoneBandPath(nervePathPoints, safeZonePathPoints)}
                            fill="rgba(249, 115, 22, 0.22)"
                            stroke="none"
                        />
                    )}

                    {polylinePath(archCurvePoints) && (
                        <path
                            d={polylinePath(archCurvePoints)}
                            fill="none"
                            stroke="#3B82F6"
                            strokeWidth="2.4"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    )}

                    {polylinePath(overlayOuterContour) && (
                        <path
                            d={polylinePath(overlayOuterContour)}
                            fill="none"
                            stroke="#EF4444"
                            strokeWidth="2.2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    )}

                    {polylinePath(overlayInnerContour) && (
                        <path
                            d={polylinePath(overlayInnerContour)}
                            fill="none"
                            stroke="#F87171"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    )}

                    {polylinePath(overlayBaseGuide) && (
                        <path
                            d={polylinePath(overlayBaseGuide)}
                            fill="none"
                            stroke="#FB7185"
                            strokeWidth="1.8"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                        />
                    )}

                    {polylinePath(nervePathPoints) && (
                        <path
                            d={polylinePath(nervePathPoints)}
                            fill="none"
                            stroke="#EF4444"
                            strokeWidth="2.4"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeDasharray="5 5"
                        />
                    )}

                    {widthLineStart && widthLineEnd && (
                        <line
                            x1={widthLineStart.x}
                            y1={widthLineStart.y}
                            x2={widthLineEnd.x}
                            y2={widthLineEnd.y}
                            stroke="#1E88E5"
                            strokeWidth="2"
                            strokeLinecap="round"
                        />
                    )}

                    {overlaySectorLines.map((line, index) => {
                        const start = toPoint(line?.start);
                        const end = toPoint(line?.end);
                        if (!start || !end) return null;

                        return (
                            <line
                                key={`sector-${index}`}
                                x1={start.x}
                                y1={start.y}
                                x2={end.x}
                                y2={end.y}
                                stroke="#F43F5E"
                                strokeWidth="1.4"
                                strokeLinecap="round"
                                opacity="0.85"
                            />
                        );
                    })}
                </svg>
            </div>

            <div
                style={{
                    position: 'absolute',
                    right: '10px',
                    top: '10px',
                    display: 'flex',
                    gap: '6px',
                    flexWrap: 'wrap',
                    maxWidth: '75%'
                }}
            >
                <LegendPill label="Arch" color="#3B82F6" />
                <LegendPill label="IAN" color="#EF4444" />
                <LegendPill label="Safe Zone" color="#F97316" />
            </div>
        </div>
    );
}

function LegendPill({ label, color }) {
    return (
        <span
            style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: '6px',
                padding: '4px 8px',
                borderRadius: '999px',
                background: 'rgba(15, 23, 42, 0.65)',
                border: '1px solid rgba(148, 163, 184, 0.45)',
                color: '#E2E8F0',
                fontSize: '0.72rem',
                fontWeight: 600
            }}
        >
            <span
                style={{
                    width: '8px',
                    height: '8px',
                    borderRadius: '999px',
                    background: color
                }}
            />
            {label}
        </span>
    );
}
