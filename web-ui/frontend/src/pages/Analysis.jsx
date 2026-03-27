import React, { useState, useEffect } from 'react';
import {
    Brain, Ruler, ArrowsLeftRight, Warning, CheckCircle,
    FileText, Spinner
} from '@phosphor-icons/react';
import { useSearchParams, useNavigate } from 'react-router-dom';
import { caseService } from '../services/caseService';
import { analysisService } from '../services/analysisService';
import AiImageViewer from '../components/AiImageViewer';

export default function Analysis() {
    const [searchParams] = useSearchParams();
    const navigate = useNavigate();
    const urlCaseId = searchParams.get('id');
    const [activeCaseId, setActiveCaseId] = useState(urlCaseId || localStorage.getItem('implantAI_lastCaseId') || '');
    const [availableCases, setAvailableCases] = useState([]);
    const [view, setView] = useState('start'); // start, loading, result
    const [caseData, setCaseData] = useState(null);
    const [analysisData, setAnalysisData] = useState(null);

    useEffect(() => {
        const loadCase = async () => {
            try {
                const allCases = await caseService.getCases();
                setAvailableCases(allCases || []);

                const resolvedCaseId = activeCaseId || allCases?.[0]?.id;
                if (!resolvedCaseId) {
                    setView('start');
                    return;
                }

                if (String(resolvedCaseId) !== String(activeCaseId)) {
                    setActiveCaseId(String(resolvedCaseId));
                    return;
                }

                const data = await caseService.getCaseById(resolvedCaseId);
                if (data) {
                    setCaseData(data);
                    if (data.status === 'Analysis Complete') {
                        loadResults(resolvedCaseId);
                    } else {
                        setView('start');
                    }
                }
            } catch (e) { console.error(e); }
        };
        loadCase();
    }, [activeCaseId]);

    useEffect(() => {
        if (!urlCaseId) return;
        setActiveCaseId(urlCaseId);
    }, [urlCaseId]);

    const runAnalysis = async () => {
        if (!activeCaseId) return;
        setView('loading');
        try {
            const result = await analysisService.run(activeCaseId);
            localStorage.setItem('implantAI_lastCaseId', String(activeCaseId));

            // Artificial delay for UX if too fast
            setTimeout(() => {
                setAnalysisData(result);
                setView('result');
            }, 1500);

        } catch (e) {
            alert("Analysis failed: " + e.message);
            setView('start');
        }
    };

    const loadResults = async (id) => {
        try {
            const data = await analysisService.getResult(id);
            setAnalysisData(data);
            setView('result');
        } catch (e) { console.error(e); }
    };

    return (
        <div className="analysis-page-content" style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
            <div className="page-header" style={{ marginBottom: '2rem', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: '#1E293B' }}>AI Analysis</h1>
                <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                    <select
                        value={activeCaseId}
                        onChange={(e) => setActiveCaseId(e.target.value)}
                        style={{ padding: '0.5rem 0.75rem', borderRadius: '8px', border: '1px solid #CBD5E1', minWidth: '260px' }}
                    >
                        <option value="">Select patient case</option>
                        {availableCases.map((entry) => (
                            <option key={entry.id} value={entry.id}>
                                {entry.fname} {entry.lname} ({entry.case_id || entry.id})
                            </option>
                        ))}
                    </select>
                    {caseData && (
                        <div style={{ background: '#EFF6FF', color: '#2563EB', padding: '0.5rem 1rem', borderRadius: '8px', fontWeight: 600 }}>
                            Case: <span>{caseData.fname} {caseData.lname}</span>
                        </div>
                    )}
                </div>
            </div>

            <div className="analysis-container" style={{ display: 'flex', gap: '2rem', flex: 1, height: '100%' }}>

                {/* 1. Start View */}
                {view === 'start' && (
                    <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'white', borderRadius: '16px', boxShadow: '0 4px 20px -5px rgba(0,0,0,0.1)', padding: '3rem', height: '100%' }}>
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', maxWidth: '450px' }}>
                            <div style={{ width: '80px', height: '80px', background: '#EFF6FF', borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#2563EB', fontSize: '40px', marginBottom: '1.5rem' }}>
                                <Brain weight="bold" />
                            </div>
                            <h2 style={{ fontSize: '1.75rem', fontWeight: 700, color: '#1E293B', marginBottom: '1rem' }}>Start AI Analysis</h2>
                            <p style={{ color: '#64748B', fontSize: '1rem', lineHeight: 1.6, marginBottom: '2rem' }}>
                                Backend dicom processing will detect arch form, trace the inferior alveolar nerve, and measure bone dimensions.
                            </p>
                            <button
                                onClick={runAnalysis}
                                disabled={!activeCaseId}
                                style={{ background: activeCaseId ? '#0066CC' : '#94A3B8', color: 'white', width: '100%', padding: '1rem', borderRadius: '8px', border: 'none', fontSize: '1rem', fontWeight: 600, cursor: activeCaseId ? 'pointer' : 'not-allowed', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.75rem' }}
                            >
                                <Brain weight="bold" /> Analyze with AI
                            </button>
                            {!activeCaseId && <p style={{ marginTop: '1rem', color: '#EF4444', fontSize: '0.9rem' }}>Please select a patient case first.</p>}
                        </div>
                    </div>
                )}

                {/* 2. Loading View */}
                {view === 'loading' && (
                    <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'white', borderRadius: '16px', boxShadow: '0 4px 20px -5px rgba(0,0,0,0.1)', padding: '3rem', height: '100%' }}>
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', maxWidth: '450px' }}>
                            <Spinner size={48} className="animate-spin" style={{ color: '#3B82F6', marginBottom: '1rem' }} /> {/* Add animation css or inline */}
                            <style>{`@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } } .animate-spin { animation: spin 1s linear infinite; }`}</style>
                            <h2 style={{ fontSize: '1.75rem', fontWeight: 700, color: '#1E293B', marginBottom: '1rem' }}>Processing CBCT Scan...</h2>
                            <p style={{ color: '#64748B', fontSize: '1rem' }}>Running dicom processing and generating report. Please wait.</p>
                        </div>
                    </div>
                )}

                {/* 3. Result View */}
                {view === 'result' && analysisData && (
                    <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '2rem', height: '100%', width: '100%' }}>
                        {/* Left: Viewer */}
                        <div style={{ background: '#000', borderRadius: '12px', overflow: 'hidden', position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                            <AiImageViewer analysisData={analysisData} />
                        </div>

                        {/* Right: Metrics */}
                        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem', overflowY: 'auto' }}>

                            <div style={{ background: '#F8FAFC', border: '1px solid #E2E8F0', borderRadius: '12px', padding: '1rem' }}>
                                <div style={{ fontSize: '0.85rem', fontWeight: 700, color: '#334155', marginBottom: '0.5rem' }}>
                                    IAN Status
                                </div>
                                <div style={{ fontSize: '0.9rem', color: '#475569', lineHeight: 1.5 }}>
                                    {analysisData.ian_status_message || 'IAN path overlay displayed in red when detected.'}
                                </div>
                                {analysisData.recommendation_line && (
                                    <div style={{ marginTop: '0.75rem', fontSize: '0.85rem', color: '#334155' }}>
                                        {analysisData.recommendation_line}
                                    </div>
                                )}
                            </div>

                            <MetricCard icon={<Ruler />} title="Bone Height" value={analysisData.bone_height} unit="mm" />
                            <MetricCard icon={<ArrowsLeftRight />} title="Bone Width (36)" value={analysisData.bone_width_36} unit="mm" />
                            <MetricCard icon={<Warning />} title="Nerve Distance" value={analysisData.nerve_distance} unit="mm" />

                            <div style={{ background: '#ECFDF5', padding: '1.5rem', borderRadius: '12px', border: '1px solid #10B981' }}>
                                <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#059669', marginBottom: '0.5rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                    <CheckCircle weight="bold" /> Safe Implant Length
                                </div>
                                <div>
                                    <span style={{ fontSize: '1.5rem', fontWeight: 700, color: '#059669' }}>{analysisData.safe_implant_length}</span>
                                    <span style={{ fontSize: '0.9rem', color: '#059669', fontWeight: 500, marginLeft: '4px' }}>mm</span>
                                </div>
                            </div>

                            {(analysisData.clinical_report || analysisData.patient_explanation) && (
                                <div style={{ background: '#FFFFFF', padding: '1rem', borderRadius: '12px', border: '1px solid #E2E8F0' }}>
                                    <div style={{ fontSize: '0.85rem', fontWeight: 700, color: '#334155', marginBottom: '0.5rem' }}>
                                        AI Clinical Report
                                    </div>
                                    <div style={{ fontSize: '0.9rem', color: '#334155', lineHeight: 1.5, marginBottom: analysisData.patient_explanation ? '0.75rem' : 0 }}>
                                        {analysisData.clinical_report || 'No report generated.'}
                                    </div>
                                    {analysisData.patient_explanation && (
                                        <div style={{ fontSize: '0.88rem', color: '#64748B', lineHeight: 1.5 }}>
                                            {analysisData.patient_explanation}
                                        </div>
                                    )}
                                </div>
                            )}

                            <div style={{ marginTop: 'auto', display: 'flex', gap: '1rem' }}>
                                <button
                                    onClick={() => navigate(`/report?id=${activeCaseId}`)}
                                    style={{ background: '#10B981', color: 'white', padding: '1rem', borderRadius: '8px', border: 'none', fontWeight: 600, cursor: 'pointer', width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '0.5rem' }}
                                >
                                    <FileText weight="bold" /> Generate Report
                                </button>
                            </div>
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}

function MetricCard({ icon, title, value, unit }) {
    return (
        <div style={{ background: 'white', padding: '1.5rem', borderRadius: '12px', border: '1px solid #E2E8F0' }}>
            <div style={{ fontSize: '0.9rem', fontWeight: 600, color: '#64748B', marginBottom: '0.5rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                {React.cloneElement(icon, { weight: 'bold' })} {title}
            </div>
            <div>
                <span style={{ fontSize: '1.5rem', fontWeight: 700, color: '#1E293B' }}>{value || '--'}</span>
                <span style={{ fontSize: '0.9rem', color: '#94A3B8', fontWeight: 500, marginLeft: '4px' }}>{unit}</span>
            </div>
        </div>
    );
}
