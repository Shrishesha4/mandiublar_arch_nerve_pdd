import React from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FileArrowUp, CheckCircle, WarningCircle } from '@phosphor-icons/react';
import { caseService } from '../services/caseService';
import { analysisService } from '../services/analysisService';
import './CaseRecord.css';

const inferRoleFromFilename = (filename = '') => {
    const lower = filename.toLowerCase();
    if (lower.startsWith('arch_') || lower.includes('arch')) return 'ARCH';
    if (lower.startsWith('ian_') || lower.includes('ian')) return 'IAN';
    return 'GENERAL';
};

const prettyDate = (value) => {
    if (!value) return 'N/A';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return 'N/A';
    return date.toLocaleString();
};

export default function CaseRecord() {
    const navigate = useNavigate();
    const { caseId } = useParams();

    const [loading, setLoading] = React.useState(true);
    const [caseRow, setCaseRow] = React.useState(null);
    const [analysis, setAnalysis] = React.useState(null);
    const [files, setFiles] = React.useState([]);

    const [archFile, setArchFile] = React.useState(null);
    const [ianFile, setIanFile] = React.useState(null);
    const [analyzing, setAnalyzing] = React.useState(false);
    const [banner, setBanner] = React.useState('');

    const loadData = React.useCallback(async () => {
        if (!caseId) return;
        setLoading(true);
        try {
            const resolvedCase = await caseService.getCaseById(caseId);
            setCaseRow(resolvedCase || null);

            if (resolvedCase?.id) {
                try {
                    const result = await analysisService.getResult(resolvedCase.id);
                    setAnalysis(result || null);
                } catch (_e) {
                    setAnalysis(null);
                }
            } else {
                setAnalysis(null);
            }

            const uploadedFiles = await caseService.getCaseFiles(caseId);
            setFiles(uploadedFiles || []);
        } finally {
            setLoading(false);
        }
    }, [caseId]);

    React.useEffect(() => {
        loadData();
    }, [loadData]);

    const uploadRoleFile = async (role, file) => {
        if (!file) return true;
        const renamedFile = new File(
            [file],
            `${role.toLowerCase()}_${Date.now()}_${file.name}`,
            { type: file.type || 'application/octet-stream' },
        );
        const formData = new FormData();
        formData.append('file', renamedFile);
        const upload = await caseService.uploadCaseFile(caseId, formData);
        return Boolean(upload);
    };

    const handleStartAnalysis = async () => {
        const archReady = Boolean(archFile) || archUploaded;
        const ianReady = Boolean(ianFile) || ianUploaded;
        if (!archReady || !ianReady) {
            setBanner('Please provide both ARCH and IAN files before starting analysis.');
            return;
        }

        setBanner('');
        setAnalyzing(true);

        try {
            const archOk = await uploadRoleFile('ARCH', archFile);
            const ianOk = await uploadRoleFile('IAN', ianFile);
            if (!archOk || !ianOk) {
                setBanner('Could not upload required files. Please retry.');
                return;
            }

            const result = await analysisService.run(caseId);
            if (!result) {
                setBanner('Analysis could not be completed. Please retry.');
                return;
            }

            setArchFile(null);
            setIanFile(null);
            setAnalysis(result);
            await loadData();
            setBanner('Analysis complete. The results look good, but AI is not 100% accurate. Please consider expert clinical judgment.');
        } catch (_e) {
            setBanner('Analysis failed. Please verify files and try again.');
        } finally {
            setAnalyzing(false);
        }
    };

    if (loading) {
        return (
            <div className="case-record-page">
                <div className="case-record-header">
                    <h1>Case Details</h1>
                </div>
                <p className="case-record-muted">Loading case...</p>
            </div>
        );
    }

    if (!caseRow) {
        return (
            <div className="case-record-page">
                <button className="case-record-back" onClick={() => navigate('/dashboard')}>
                    <ArrowLeft size={16} /> Back to Dashboard
                </button>
                <h1>Case Not Found</h1>
            </div>
        );
    }

    const archUploaded = files.some((file) => inferRoleFromFilename(file.filename) === 'ARCH');
    const ianUploaded = files.some((file) => inferRoleFromFilename(file.filename) === 'IAN');
    const hasImage = Boolean(analysis?.opg_image_base64);

    const steps = [
        { title: 'Step 1: Case selected', done: Boolean(caseRow) },
        { title: 'Step 2: ARCH input ready', done: Boolean(archFile) || archUploaded },
        { title: 'Step 3: IAN input ready', done: Boolean(ianFile) || ianUploaded },
        { title: 'Step 4: Analysis result', done: Boolean(analysis) },
    ];

    return (
        <div className="case-record-page">
            <div className="case-record-toolbar">
                <button className="case-record-back" onClick={() => navigate('/dashboard')}>
                    <ArrowLeft size={16} /> Back to Dashboard
                </button>
            </div>

            <div className="page-header">
                <h1>Report Details</h1>
                <p className="case-record-muted">Case ID: {caseRow.case_id || caseRow.id}</p>
            </div>

            <section className="case-record-card">
                <h3>Interactive Flow</h3>
                <div className="case-steps-list">
                    {steps.map((step) => (
                        <div key={step.title} className={`case-step-item ${step.done ? 'done' : ''}`}>
                            <span className="case-step-dot" />
                            <span>{step.title}</span>
                        </div>
                    ))}
                </div>
            </section>

            {banner && <div className="case-record-banner">{banner}</div>}

            <div className="case-record-grid">
                <section className="case-record-card">
                    <h3>Patient</h3>
                    <div className="case-record-info-grid">
                        <div>
                            <label>Name</label>
                            <span>{caseRow.fname} {caseRow.lname}</span>
                        </div>
                        <div>
                            <label>Age</label>
                            <span>{caseRow.patient_age || 'N/A'}</span>
                        </div>
                        <div>
                            <label>Tooth</label>
                            <span>{caseRow.tooth_number || 'N/A'}</span>
                        </div>
                        <div>
                            <label>Status</label>
                            <span>{caseRow.status || 'Pending Analysis'}</span>
                        </div>
                    </div>
                </section>

                <section className="case-record-card">
                    <h3>Start Analysis</h3>
                    <p className="case-record-muted">Provide both files below, then start analysis for this case.</p>

                    <div className="case-upload-row">
                        <div>
                            <label className="case-upload-label">ARCH DCM</label>
                            <input
                                type="file"
                                accept=".dcm,.zip"
                                onChange={(e) => setArchFile(e.target.files?.[0] || null)}
                            />
                            <div className={`case-upload-status ${archUploaded ? 'ok' : 'missing'}`}>
                                {archUploaded ? <CheckCircle size={16} /> : <WarningCircle size={16} />}
                                {archUploaded ? 'ARCH uploaded' : 'ARCH missing'}
                            </div>
                        </div>
                    </div>

                    <div className="case-upload-row">
                        <div>
                            <label className="case-upload-label">IAN (DCM/JPG/PNG)</label>
                            <input
                                type="file"
                                accept=".dcm,.jpg,.jpeg,.png,.zip"
                                onChange={(e) => setIanFile(e.target.files?.[0] || null)}
                            />
                            <div className={`case-upload-status ${ianUploaded ? 'ok' : 'missing'}`}>
                                {ianUploaded ? <CheckCircle size={16} /> : <WarningCircle size={16} />}
                                {ianUploaded ? 'IAN uploaded' : 'IAN missing'}
                            </div>
                        </div>
                    </div>

                    <button
                        className="case-upload-btn"
                        onClick={handleStartAnalysis}
                        disabled={analyzing}
                    >
                        <FileArrowUp size={16} /> {analyzing ? 'Analyzing...' : 'Start Analysis'}
                    </button>
                </section>
            </div>

            <section className="case-record-card">
                <h3>Uploaded Files</h3>
                {files.length === 0 ? (
                    <p className="case-record-muted">No files uploaded for this case yet.</p>
                ) : (
                    <div className="case-files-list">
                        {files.map((file) => (
                            <div key={file.id} className="case-file-item">
                                <span className="case-file-name">{file.filename}</span>
                                <span className="case-file-meta">{inferRoleFromFilename(file.filename)} • {prettyDate(file.uploaded_at)}</span>
                            </div>
                        ))}
                    </div>
                )}
            </section>

            <section className="case-record-card">
                <h3>Latest Analysis Summary</h3>
                {!analysis ? (
                    <p className="case-record-muted">No analysis result yet. Upload files and run analysis to populate report details.</p>
                ) : (
                    <>
                        {hasImage && (
                            <div className="case-analysis-image-wrap">
                                <img
                                    src={`data:image/png;base64,${analysis.opg_image_base64}`}
                                    alt="Analysis result"
                                    className="case-analysis-image"
                                />
                            </div>
                        )}

                        <div className="case-record-info-grid">
                            <div>
                                <label>Bone Height</label>
                                <span>{analysis.bone_height || '--'} mm</span>
                            </div>
                            <div>
                                <label>Bone Width</label>
                                <span>{analysis.bone_width_36 || '--'} mm</span>
                            </div>
                            <div>
                                <label>Nerve Distance</label>
                                <span>{analysis.nerve_distance || '--'} mm</span>
                            </div>
                            <div>
                                <label>Safe Implant Length</label>
                                <span>{analysis.safe_implant_length || '--'} mm</span>
                            </div>
                        </div>

                        <div className="case-record-note">
                            The results look good, but AI is not 100% accurate. Please consider expert clinical judgment.
                        </div>
                    </>
                )}
            </section>
        </div>
    );
}
